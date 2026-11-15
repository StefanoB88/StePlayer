// Time from clicking a rail tab to its content (dev measurement).
new Promise(function (resolve) {
  var plan = [['Live', '.ch-row'], ['Film', '.vgrid .poster'], ['Serie', '.vgrid .poster'], ['Home', '.hrow .poster'], ['Live', '.ch-row'], ['Film', '.vgrid .poster']];
  var out = [];
  function item(label) {
    var all = document.querySelectorAll('.rail-item');
    for (var i = 0; i < all.length; i++) if (all[i].textContent.trim() === label) return all[i];
    return null;
  }
  function visible(sel) {
    var all = document.querySelectorAll(sel);
    for (var i = 0; i < all.length; i++) if (all[i].offsetParent !== null) return true;
    return false;
  }
  (function next(i) {
    if (i >= plan.length) { resolve(out.join(', ')); return; }
    var p = plan[i];
    var t0 = performance.now();
    item(p[0]).click();
    var t = setInterval(function () {
      if (visible(p[1]) || performance.now() - t0 > 15000) {
        clearInterval(t);
        out.push(p[0] + '=' + Math.round(performance.now() - t0) + 'ms');
        setTimeout(function () { next(i + 1); }, 800);
      }
    }, 10);
  })(0);
})
