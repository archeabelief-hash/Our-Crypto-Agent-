const APP_VERSION = '2026-04-28-v2';

(function(){
  const links = document.querySelectorAll('a[href$=".html"]');
  links.forEach(link => {
    if (!link.href.includes('?v=')) {
      link.href = link.href + '?v=' + APP_VERSION;
    }
  });
})();
