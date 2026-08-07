(() => {
  const productionHost = "ultramancode.github.io";
  const consent = __md_get("__consent");
  const clarityGranted = Boolean(consent && consent.clarity);

  // Keep local previews and forks out of production analytics.
  if (window.location.hostname !== productionHost) {
    return;
  }

  // When consent is rejected or later withdrawn, remove Clarity's
  // first-party state locally without contacting Clarity.
  if (!clarityGranted) {
    for (const name of ["_clck", "_clsk"]) {
      // Clear both host-only and domain-scoped variants. Clarity may write
      // cookies against the current root domain.
      document.cookie = `${name}=; Max-Age=0; Path=/; SameSite=Lax`;
      document.cookie =
        `${name}=; Max-Age=0; Path=/; Domain=${window.location.hostname}; SameSite=Lax`;
    }

    try {
      window.sessionStorage.removeItem("_cltk");
    } catch {
      // Storage can be unavailable in restricted browser contexts.
    }

    return;
  }

  // Queue ConsentV2 before loading Clarity so analytics storage is only
  // granted after explicit consent. Advertising storage is never granted.
  (function (c, l, a, r, i, t, y) {
    c[a] = c[a] || function () {
      (c[a].q = c[a].q || []).push(arguments);
    };

    c[a]("consentv2", {
      ad_Storage: "denied",
      analytics_Storage: "granted"
    });

    t = l.createElement(r);
    t.async = true;
    t.src = "https://www.clarity.ms/tag/" + i;
    y = l.getElementsByTagName(r)[0];
    y.parentNode.insertBefore(t, y);
  })(window, document, "clarity", "script", "xyizborcjq");
})();
