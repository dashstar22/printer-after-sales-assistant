export function fragment(id) { return document.getElementById(id).content.cloneNode(true); }
export function showText(element, text) { element.textContent = text; element.hidden = !text; }
export function bindRefresh(fragment, action, pending) {
  const button = fragment.querySelector('[data-refresh]');
  button.disabled = !!pending;
  button.addEventListener('click', action);
}
