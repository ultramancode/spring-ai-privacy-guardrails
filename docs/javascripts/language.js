// Instant navigation replaces page metadata but preserves the root HTML element.
document$.subscribe(() => {
  const canonical = document.querySelector('link[rel="canonical"]');
  const alternate = Array.from(
    document.querySelectorAll('link[rel="alternate"][hreflang]')
  ).find((link) => link.href === canonical?.href);

  if (alternate) {
    document.documentElement.lang = alternate.hreflang;
  }
});
