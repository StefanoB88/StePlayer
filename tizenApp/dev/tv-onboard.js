// Completes onboarding on the TV with the fake API Xtream test server (dev only).
(function () {
  return new Promise(function (resolve) {
    function click(el) { el.click(); }
    function find(sel, text) {
      var all = document.querySelectorAll(sel);
      for (var i = 0; i < all.length; i++) if (!text || all[i].textContent.indexOf(text) >= 0) return all[i];
      return null;
    }
    var t0 = Date.now();
    var check = find('.checkrow');
    if (check && !check.classList.contains('checked')) click(check);
    var cont = find('.btn', 'Continua');
    if (cont) click(cont);
    setTimeout(function () {
      var inputs = document.querySelectorAll('.field input');
      var vals = ['Prova TV', 'http://192.168.50.112:8081', 'demo', 'demo'];
      for (var i = 0; i < inputs.length && i < 4; i++) inputs[i].value = vals[i];
      var save = find('.btn', 'Salva e connetti');
      click(save);
      var poll = setInterval(function () {
        if (document.querySelector('.rail')) { clearInterval(poll); resolve('shell after ' + (Date.now() - t0) + ' ms'); }
        if (Date.now() - t0 > 240000) { clearInterval(poll); resolve('timeout: ' + document.body.innerText.slice(0, 200)); }
      }, 250);
    }, 800);
  });
})()
