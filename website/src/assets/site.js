// Harmony website: mobile menu, FAQ deep links, and the current section in
// the navigation. Everything on the page works without this script; it only
// adds these conveniences.
(() => {
  const toggle = document.querySelector('.menu-toggle');
  const menu = document.getElementById('mobile-menu');

  if (toggle && menu) {
    const setOpen = (open, { focusToggle = false } = {}) => {
      toggle.setAttribute('aria-expanded', String(open));
      menu.hidden = !open;
      if (open) menu.querySelector('a')?.focus();
      else if (focusToggle) toggle.focus();
    };
    toggle.addEventListener('click', () => setOpen(toggle.getAttribute('aria-expanded') !== 'true'));
    menu.addEventListener('click', (e) => { if (e.target.closest('a')) setOpen(false); });
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && toggle.getAttribute('aria-expanded') === 'true') setOpen(false, { focusToggle: true });
    });
    // Leaving the menu open while resizing to the desktop layout would hide it
    // behind a toggle that no longer shows.
    matchMedia('(min-width: 55em)').addEventListener('change', (e) => { if (e.matches) setOpen(false); });
  }

  // A link to #faq-offline (or any closed <details>) opens it.
  const openTarget = () => {
    const id = decodeURIComponent(location.hash.slice(1));
    const el = id && document.getElementById(id);
    if (el && el.tagName === 'DETAILS') el.open = true;
  };
  window.addEventListener('hashchange', openTarget);
  openTarget();

  // Mark the navigation link of the section being read.
  const links = [...document.querySelectorAll('.primary-nav a[href^="#"]')];
  const sections = links.map((a) => document.querySelector(a.getAttribute('href'))).filter(Boolean);
  if ('IntersectionObserver' in window && sections.length) {
    const visible = new Map();
    const io = new IntersectionObserver((entries) => {
      entries.forEach((e) => visible.set(e.target, e.isIntersecting));
      const current = sections.find((s) => visible.get(s));
      links.forEach((a) => {
        if (current && a.getAttribute('href') === `#${current.id}`) a.setAttribute('aria-current', 'true');
        else a.removeAttribute('aria-current');
      });
    }, { rootMargin: '-40% 0px -55% 0px' });
    sections.forEach((s) => io.observe(s));
  }
})();
