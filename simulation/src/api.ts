import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import type { Focus, Frame, ScenarioInfo, SimParams, StaticInfo, Status, SweepPoint, SweepRequest } from "./types";

export const api = {
  listScenarios: () => invoke<ScenarioInfo[]>("list_scenarios"),
  createSim: (id: string, params: SimParams) => invoke<StaticInfo>("create_sim", { id, params }),
  setRunning: (running: boolean) => invoke<void>("set_running", { running }),
  /** Simulated minutes per real second; null runs as fast as possible. */
  setSpeed: (speed: number | null) => invoke<void>("set_speed", { speed }),
  advance: (minutes: number) => invoke<void>("advance", { minutes }),
  status: (eventsFrom: number, seriesFrom: number) => invoke<Status | null>("status", { eventsFrom, seriesFrom }),
  setFocus: (focus: Focus) => invoke<void>("set_focus", { focus }),
  sweep: (id: string, params: SimParams, request: SweepRequest) => invoke<SweepPoint[]>("sweep", { id, params, request }),
  onSweepProgress: (cb: (p: { done: number; total: number }) => void): Promise<UnlistenFn> =>
    listen<{ done: number; total: number }>("sweep-progress", (e) => cb(e.payload)),

  async frame(): Promise<Frame | null> {
    const buf = await invoke<ArrayBuffer>("frame");
    if (!buf || buf.byteLength < 8) return null;
    const head = new DataView(buf, 0, 8);
    const t = head.getFloat32(0, true);
    const n = head.getUint32(4, true);
    const f32 = new Float32Array(buf, 8, n * 3);
    const u32 = new Uint32Array(buf, 8, n * 3);
    const xy = new Float32Array(n * 2);
    const flags = new Uint32Array(n);
    for (let i = 0; i < n; i++) {
      xy[i * 2] = f32[i * 3];
      xy[i * 2 + 1] = f32[i * 3 + 1];
      flags[i] = u32[i * 3 + 2];
    }
    return { t, n, xy, flags };
  },
};
