// Opens Impostazioni → Aggiungi lista on the TV (dev).
new Promise(function (resolve) {
  function byText(sel, text) {
    var all = document.querySelectorAll(sel);
    for (var i = 0; i < all.length; i++) if (all[i].textContent.indexOf(text) >= 0) return all[i];
    return null;
  }
  setTimeout(function () {
    byText('.rail-item', 'Impostazioni').click();
    setTimeout(function () {
      byText('[data-focus]', 'Aggiungi lista').click();
      setTimeout(function () { resolve(document.body.innerText.slice(0, 400).replace(/\n/g, ' | ')); }, 6000);
    }, 1500);
  }, 2500);
})
