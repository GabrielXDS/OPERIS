// Firestore-like in-memory fake with optimistic-locking transactions,
// enough for recordOperations and the alertTargets path used by the triggers.
import {Timestamp} from "firebase-admin/firestore";

const clone = v => {
  if (v === null || v === undefined) return v;
  if (typeof v !== "object") return v;
  if (typeof v.toMillis === "function") return v;
  if (v.constructor?.name === "DeleteTransform") return v;
  if (Array.isArray(v)) return v.map(clone);
  const out = {};
  for (const k of Object.keys(v)) out[k] = clone(v[k]);
  return out;
};
const valueOf = v => (v && typeof v.toMillis === "function") ? v.toMillis() : v;
const isDelete = v => v && typeof v === "object" && (v.constructor?.name === "DeleteTransform" || v._methodName === "FieldValueDelete");
const merge = (base, patch) => {
  const out = {...clone(base)};
  for (const [k, v] of Object.entries(clone(patch) ?? {})) {
    if (isDelete(v)) delete out[k];
    else out[k] = v;
  }
  return out;
};
const isRow = e => e && typeof e === "object" && Object.prototype.hasOwnProperty.call(e, "data") && Object.prototype.hasOwnProperty.call(e, "v");

export function inmem(store = new Map()) {
  const getRow = path => {
    const e = store.get(path);
    return isRow(e) ? e : {v: 0, data: e};
  };
  const snap = (row, path) => ({
    id: path.split("/").at(-1),
    exists: row.data !== undefined,
    data: () => clone(row.data),
    ref: null,
  });

  function ref(path) {
    const value = {
      path,
      id: path.split("/").at(-1),
      async get() {
        const s = snap(getRow(path), path);
        s.ref = value;
        return s;
      },
      async set(data, opts = {}) {
        const row = getRow(path);
        const merged = opts.merge && row.data !== undefined ? merge(row.data, data) : clone(data);
        store.set(path, {v: row.v + 1, data: merged});
      },
      async update(partial) {
        const row = getRow(path);
        const base = row.data !== undefined ? clone(row.data) : {};
        store.set(path, {v: row.v + 1, data: merge(base, partial)});
      },
    };
    return value;
  }

  function collection(name) {
    const clauses = [];
    const make = () => {
      const q = {
        name,
        where(field, op, val) { clauses.push({field, op, val}); return make(); },
        orderBy() { return make(); },
        limit() { return make(); },
        async get() {
          const rows = [...store.entries()]
            .filter(([p]) => p.startsWith(name + "/"))
            .filter(([, e]) => {
              const row = isRow(e) ? e : {v: 0, data: e};
              return clauses.every(c => {
                const actual = valueOf(row.data[c.field]);
                const want = valueOf(c.val);
                return c.op === "==" ? actual === want : c.op === ">" ? actual > want : true;
              });
            });
          return {docs: rows.map(([p, e]) => snap(isRow(e) ? e : {v: 0, data: e}, p))};
        },
        count() {
          return {
            async get() {
              const res = await q.get();
              return {data: () => ({count: res.docs.length})};
            },
          };
        },
      };
      return q;
    };
    return make();
  }

  const db = {
    doc: ref,
    collection,
    async getAll(...refs) { return Promise.all(refs.map(r => r.get())); },
    async runTransaction(work) {
      for (let attempts = 0; attempts < 6; attempts++) {
        const reads = new Map();
        const writes = [];
        function snapshot(path) {
          if (!reads.has(path)) {
            const row = getRow(path);
            reads.set(path, {v: row.v, data: clone(row.data)});
          }
          return reads.get(path);
        }
        const tx = {
          async get(refLike) {
            const s = snap(snapshot(refLike.path), refLike.path);
            return s;
          },
          set(refLike, data, opts = {}) {
            writes.push({path: refLike.path, kind: "set", data: clone(data), merge: !!opts.merge});
            snapshot(refLike.path);
          },
          update(refLike, partial) {
            writes.push({path: refLike.path, kind: "update", data: clone(partial)});
            snapshot(refLike.path);
          },
          getAll: (...refs) => Promise.all(refs.map(r => tx.get(r))),
        };
        let result;
        try {
          result = await work(tx);
        } catch (e) {
          throw e;
        }
        let conflict = false;
        for (const w of writes) {
          const row = getRow(w.path);
          if ((reads.get(w.path)?.v ?? -1) !== row.v) { conflict = true; break; }
        }
        if (conflict) continue;
        for (const w of writes) {
          const row = getRow(w.path);
          if (w.kind === "set") {
            const base = row.data !== undefined ? clone(row.data) : {};
            const data = w.merge ? merge(base, w.data) : w.data;
            store.set(w.path, {v: row.v + 1, data});
          } else {
            const base = row.data !== undefined ? clone(row.data) : {};
            store.set(w.path, {v: row.v + 1, data: merge(base, w.data)});
          }
        }
        return result;
      }
      throw new Error("inmem: transaction retries exhausted");
    },
  };
  return db;
}

export function fakeMessaging(handler) {
  const calls = [];
  const messaging = {
    sendEachForMulticast: async payload => {
      calls.push(payload);
      const responses = handler(payload);
      return {responses};
    },
  };
  const api = () => messaging;
  return Object.assign(api, {calls});
}

export const okResps = req => req.tokens.map(() => ({success: true}));
export const failToken = code => ({success: false, error: {code}});