-- mosimo-bsaf-feed:v2 (managed by appview/bsaf/prepare.mts)
function handle()
  local limit = tonumber(params.limit or "20")
  local offset = tonumber(params.cursor or "0")
  if not limit or limit < 1 or limit > 100 or limit % 1 ~= 0
      or not offset or offset < 0 or offset > 9007199254740891 or offset % 1 ~= 0 then
    error("Invalid limit or cursor")
  end

  local filter = ""
  local values = {}
  if params.did then
    filter = filter .. " AND r.did = ?"
    table.insert(values, params.did)
  end
  if params.uri then
    filter = filter .. " AND r.uri = ?"
    table.insert(values, params.uri)
  end
  -- SQLite merges two index-ordered streams, rather than sorting all history
  -- through a cross-collection OR. Pagination applies to the combined order.
  local sql = [[
    SELECT r.uri, r.did, r.collection, r.cid, r.record, r.indexed_at,
           NULL AS fetched_at, NULL AS source_url
    FROM happyview_records r WHERE r.collection = 'dev.oriom.mosimo.post'
      AND CASE WHEN json_valid(r.record) THEN
        NOT EXISTS (
          SELECT 1 FROM json_each(
            CASE WHEN json_type(r.record, '$.extensions') = 'array'
              THEN json_extract(r.record, '$.extensions') ELSE '[]' END
          ) ext
          WHERE CASE WHEN ext.type = 'object' THEN
            json_extract(ext.value, '$.$type') = 'dev.oriom.mosimo.defs#bsafV1'
            AND json_type(ext.value, '$.original') IS NOT NULL
          ELSE 0 END
        )
      ELSE 0 END
  ]] .. filter .. [[
    UNION ALL
    SELECT r.uri, r.did, r.collection, r.cid, r.record, r.indexed_at,
           e.fetched_at, e.source_url
    FROM happyview_records r
    JOIN mosimo_bsaf_events e ON e.uri = r.uri AND e.cid = r.cid
    WHERE r.collection = 'app.bsky.feed.post'
      AND EXISTS (SELECT 1 FROM mosimo_bsaf_sources s WHERE s.did = r.did)
  ]] .. filter .. " ORDER BY indexed_at DESC, uri DESC LIMIT ? OFFSET ?"
  local binds = {}
  for branch = 1, 2 do
    for _, value in ipairs(values) do table.insert(binds, value) end
  end
  table.insert(binds, params.uri and 1 or limit + 1)
  table.insert(binds, params.uri and 0 or offset)
  local rows = db.raw(sql, binds)
  local records = {}
  local provenance = {}
  for i, row in ipairs(rows) do
    if i > limit then break end
    -- Only the response gains uri, matching HappyView's existing query shape.
    -- Original records, tags, langs, links and CIDs remain unchanged in SQLite.
    local value = json.decode(row.record)
    value.uri = row.uri
    table.insert(records, value)
    provenance[row.uri] = {
      did = row.did, cid = row.cid, collection = row.collection,
      indexedAt = row.indexed_at
    }
    if row.collection == "app.bsky.feed.post" then
      provenance[row.uri].retrievedAt = row.fetched_at
      provenance[row.uri].source = row.source_url
    end
  end
  if params.uri then
    if #records == 0 then error("Record not found") end
    return { record = records[1], provenance = provenance }
  end
  local result = { records = toarray(records), provenance = provenance }
  if #rows > limit then result.cursor = tostring(offset + limit) end
  return result
end
