// Open every off-site link in a new tab.
// Any anchor whose host differs from the current site (for example GitHub, Patreon,
// the wiki, or the live demo box) gets target="_blank" plus rel="noopener noreferrer"
// so it opens in a new tab and never leaves the openWCS site in the same tab. Same-site
// links (other public pages, the in-site demo) stay in the same tab as normal.
// Runs site-wide via layout.ejs, so current and future pages are covered automatically.
(function () {
  function externalize() {
    var here = window.location.host
    var anchors = document.querySelectorAll('a[href]')
    for (var i = 0; i < anchors.length; i++) {
      var a = anchors[i]
      var href = a.getAttribute('href') || ''
      // Skip in-page anchors, mailto/tel, and explicit same-tab opt-outs.
      if (href.charAt(0) === '#' || /^(mailto:|tel:)/i.test(href)) continue
      var url
      try { url = new URL(a.href, window.location.href) } catch (e) { continue }
      if (url.protocol !== 'http:' && url.protocol !== 'https:') continue
      if (url.host && url.host !== here) {
        a.setAttribute('target', '_blank')
        var rel = (a.getAttribute('rel') || '').split(/\s+/).filter(Boolean)
        if (rel.indexOf('noopener') === -1) rel.push('noopener')
        if (rel.indexOf('noreferrer') === -1) rel.push('noreferrer')
        a.setAttribute('rel', rel.join(' '))
      }
    }
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', externalize)
  } else {
    externalize()
  }
})()
