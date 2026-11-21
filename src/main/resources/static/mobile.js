/* A small, reusable accessible menu for mobile headers. No backend/API changes. */
(function () {
  'use strict';
  function setupMobileMenu() {
    const header = document.querySelector('header.header, header.topbar');
    if (!header) return;
    const nav = header.querySelector('.nav, .topbar-nav');
    if (!nav || header.querySelector('.mobile-nav-toggle')) return;
    if (!nav.id) nav.id = 'mobile-site-navigation';
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'mobile-nav-toggle';
    button.setAttribute('aria-label', 'Open navigation menu');
    button.setAttribute('aria-controls', nav.id);
    button.setAttribute('aria-expanded', 'false');
    button.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M4 7h16M4 12h16M4 17h16"/></svg>';
    nav.after(button);
    const close = () => {
      header.classList.remove('mobile-nav-open');
      button.setAttribute('aria-expanded', 'false');
      button.setAttribute('aria-label', 'Open navigation menu');
    };
    button.addEventListener('click', () => {
      const willOpen = !header.classList.contains('mobile-nav-open');
      header.classList.toggle('mobile-nav-open', willOpen);
      button.setAttribute('aria-expanded', String(willOpen));
      button.setAttribute('aria-label', willOpen ? 'Close navigation menu' : 'Open navigation menu');
    });
    document.addEventListener('click', event => {
      if (!header.contains(event.target)) close();
    });
    nav.addEventListener('click', event => {
      if (event.target.closest('a')) close();
    });
    document.addEventListener('keydown', event => {
      if (event.key === 'Escape') { close(); button.blur(); }
    });
    window.addEventListener('resize', () => {
      if (window.innerWidth > 800) close();
    });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', setupMobileMenu);
  else setupMobileMenu();
})();
