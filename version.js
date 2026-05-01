const APP_VERSION = '2026-05-advanced-ui';

(function(){
  const theme = document.createElement('link');
  theme.rel = 'stylesheet';
  theme.href = 'advanced-theme.css?v=' + APP_VERSION;
  document.head.appendChild(theme);
})();

(function(){
  const links = document.querySelectorAll('a[href$=".html"]');
  links.forEach(link => {
    if (!link.href.includes('?v=')) link.href = link.href + '?v=' + APP_VERSION;
  });
})();
