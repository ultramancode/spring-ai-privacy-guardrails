(() => {
  const productionHost = "ultramancode.github.io";
  const consent = __md_get("__consent");

  // Keep local previews and forks out of production analytics.
  if (window.location.hostname !== productionHost) {
    return;
  }

  /**
   * Remove cookies by name, clearing both host-only and domain-scoped
   * variants so that cookies written against the root domain are also covered.
   */
  function clearCookies(names) {
    const hostname = window.location.hostname;
    for (const name of names) {
      document.cookie = `${name}=; Max-Age=0; Path=/; SameSite=Lax`;
      document.cookie =
        `${name}=; Max-Age=0; Path=/; Domain=${hostname}; SameSite=Lax`;
    }
  }

  // --- Google Analytics cookie cleanup ---
  // GA4 loading is managed by the Zensical built-in integration; this
  // section only handles removal of lingering cookies after consent is
  // withdrawn or was never granted.
  if (!consent || !consent.analytics) {
    clearCookies(["_ga", "_ga_TRDLMR5WD8"]);
  }

  // --- Microsoft Clarity ---
  if (!consent || !consent.clarity) {
    // Remove Clarity's first-party cookies and session storage token.
    clearCookies(["_clck", "_clsk"]);

    try {
      window.sessionStorage.removeItem("_cltk");
    } catch {
      // Storage can be unavailable in restricted browser contexts.
    }
  } else {
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
  }
})();
