#!/bin/bash
# post_fix/report.md を再生成する。
set -eo pipefail
cd "$(dirname "$0")/../../.."

nix-shell -p python3 --run "python3 << 'PY'
import json, re

out = ['# 修正後の実験結果', '',
       '各値はシードの平均。括弧内はシード間の最小〜最大。',
       'コミット \`176c156\` 以降のモデル（孤立判定を本部・空港・地図外から再計算し、道路で孤立した地域の固定基地局を先に停波させる）での結果。',
       '']

post = [json.loads(l) for l in open('experiments/post_fix/noto_adoption.json') if l.strip()]
keys = [
    ('trapped_dead', '救助が間に合わなかった人', 'n'),
    ('trapped_to_hq_median_h', '要救助→本部（中央値, 時間）', 'h'),
    ('trapped_reached_hq', '要救助が本部に届いた割合', '%'),
    ('anpi_24h', '家族に安否（24h）', '%'),
    ('anpi_72h', '家族に安否（72h）', '%'),
    ('anpi_hq_24h', '本部が安否を把握（24h）', '%'),
    ('anpi_hq_72h', '本部が安否を把握（72h）', '%'),
    ('needs_to_ops_median_h', '避難所の不足→県（時間）', 'h'),
    ('road_cut_to_ops_median_h', '道路寸断→県（時間）', 'h'),
    ('isolated_to_ops_median_h', '孤立集落→県（時間）', 'h'),
    ('isolated_unknown_events', '48h把握されない孤立集落', 'n'),
    ('shortage_shelter_hours', '物資が尽きた避難所×時間', 'n'),
    ('unknown_cut_hits', '寸断を知らず立ち往生', 'n'),
    ('mesh_exchanges', 'メッシュ交換', 'n'),
    ('mesh_missed_share', '接触したが交換できなかった割合', '%'),
]

def fmt(unit, v):
    if v is None or v != v:
        return '—'
    if unit == '%':
        return f'{v*100:.0f}%'
    if unit == 'h':
        return f'{v:.2f}'
    if abs(v) >= 100:
        return f'{v:.0f}'
    return f'{v:.1f}'

out.append('## 能登: スマホの導入率')
out.append('')
out.append('シナリオ \`noto\`、発災から72時間、シード 2')
out.append('')
ads = [r['adoption'] for r in post]
out.append('| 指標 |' + ''.join(f' 導入率 = {a} |' for a in ads))
out.append('|---|' + '---|' * len(ads))
for k, label, unit in keys:
    row = [f'| {label} |']
    for r in post:
        v = r['mean'].get(k)
        row.append(f' {fmt(unit, v)} |')
    out.append(''.join(row))
out.append('')

# Append reports
for d, title_hint in [('experiments/post_fix/features', None),
                     ('experiments/post_fix/carnav', None),
                     ('experiments/post_fix/carnav_with_phones', None),
                     ('experiments/post_fix/ble', None),
                     ('experiments/post_fix/small', None),
                     ('experiments/post_fix/small2', None)]:
    import os
    fp = os.path.join(d, 'report.md')
    if not os.path.exists(fp):
        continue
    try:
        t = open(fp).read()
    except FileNotFoundError:
        continue
    idx = t.find('## ')
    if idx >= 0:
        out.append(t[idx:])

# Starlink-by
def parse_file(path):
    hdr = None
    for line in open(path):
        line = line.rstrip()
        if line.startswith('#'):
            hdr = line.lstrip('# ').rstrip('=').strip()
        elif line.startswith('{') and hdr:
            try:
                yield hdr, json.loads(line)
            except Exception:
                pass
            hdr = None

sb_keys = [
    ('trapped_dead', '救助が間に合わなかった人', 'n'),
    ('trapped_to_hq_median_h', '要救助→本部', 'h'),
    ('anpi_24h', '家族安否 24h', '%'),
    ('anpi_72h', '家族安否 72h', '%'),
    ('anpi_hq_24h', '本部安否 24h', '%'),
    ('anpi_hq_72h', '本部安否 72h', '%'),
    ('needs_to_ops_median_h', '不足→県', 'h'),
    ('road_cut_to_ops_median_h', '寸断→県', 'h'),
    ('isolated_to_ops_median_h', '孤立→県', 'h'),
    ('isolated_unknown_events', '48h未把握孤立', 'n'),
    ('unknown_cut_hits', '寸断を知らず立往生', 'n'),
]

base = {r['adoption']: r['mean'] for r in post}
pts = {}
for src in ['experiments/post_fix/raw/starlink_by.out', 'experiments/post_fix/raw/starlink_more.out']:
    try:
        for h_label, data in parse_file(src):
            m = re.match(r'starlink-by (\d+) at adoption=([\d.]+)', h_label)
            if m:
                h = int(m.group(1))
                a = float(m.group(2))
                pts[(a, h)] = data['mean']
    except FileNotFoundError:
        pass
for a, m in base.items():
    pts[(a, None)] = m

out.append('## 能登: Starlink の開通を前倒し（修正後、72時間、2シード）')
out.append('')
adoptions = sorted({a for (a, _) in pts.keys()})
cols = []
col_labels = []
for a in adoptions:
    for h in [None, 24, 48]:
        if (a, h) in pts:
            cols.append((a, h))
            col_labels.append(f'a={a*100:.0f}% ' + ('ベース' if h is None else f'{h}h'))

out.append('| 指標 | ' + ' | '.join(col_labels) + ' |')
out.append('|---|' + '---|' * len(cols))
for k, label, unit in sb_keys:
    row = [f'| {label} |']
    for a, h in cols:
        v = pts.get((a, h), {}).get(k)
        row.append(f' {fmt(unit, v)} |')
    out.append(''.join(row))
out.append('')

# Beam capacity
out.append('## 能登: Starlink Direct のビーム容量（修正後、72時間、2シード、--direct-data 有効）')
out.append('')
bvals = {}
try:
    for h, d in parse_file('experiments/post_fix/raw/beam.out'):
        m = re.match(r'beam=([\d.]+) at adoption=(\S+)', h)
        if m:
            bvals[(m.group(1), m.group(2))] = d['mean']
except FileNotFoundError:
    pass

bl = base[0.0]
out.append('| 指標 | 導入率0% ベースライン（SD なし） | ビーム 0.1倍 | 1倍 | 10倍 | 100倍 | 導入率30% --direct-data |')
out.append('|---|---|---|---|---|---|---|')
for k, label, unit in sb_keys:
    row = [f'| {label} |', f' {fmt(unit, bl.get(k))} |']
    for mult in ['0.1', '1', '10', '100']:
        v = bvals.get((mult, '0'), {}).get(k)
        row.append(f' {fmt(unit, v)} |')
    v = bvals.get(('1', '0.3'), {}).get(k)
    row.append(f' {fmt(unit, v)} |')
    out.append(''.join(row))

open('experiments/post_fix/report.md', 'w').write('\n'.join(out) + '\n')
print('wrote experiments/post_fix/report.md')
print(f'  lines: {sum(1 for _ in open(\"experiments/post_fix/report.md\"))}')
PY"
