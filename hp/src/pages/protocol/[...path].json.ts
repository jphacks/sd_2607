import type { APIRoute, GetStaticPaths } from 'astro';
import { listLexicons } from '../../lib/lexicons';

export const prerender = true;

// /protocol/<dir>/lexicon.json、/protocol/post.json、/protocol/getFeed.json を
// protocol/mosimo のファイルからそのまま配信する。
export const getStaticPaths: GetStaticPaths = () =>
  listLexicons().map((l) => ({
    params: { path: l.url.replace(/^\/protocol\//, '').replace(/\.json$/, '') },
    props: { json: l.json },
  }));

export const GET: APIRoute = ({ props }) =>
  new Response(props.json, {
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' },
  });
