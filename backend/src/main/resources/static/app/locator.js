// Recovery hints only. Server ownership/content/version is checked on every read.
// Never stores identity claims, tickets, draft bodies or model responses.
export function locatorFor(user) {
  const key = `printer.service.locators.v1.${user.id}`;
  function read() {
    try { const value = JSON.parse(sessionStorage.getItem(key)); return value && typeof value === 'object' ? value : {}; }
    catch { return {}; }
  }
  return Object.freeze({read,
    save(patch) {
      const next = {...read(), ...patch};
      sessionStorage.setItem(key, JSON.stringify(next));
      return next;
    },
  });
}
