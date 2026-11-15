(function (spec, gap) {
  function press(code) {
    ['keydown', 'keyup'].forEach(function (type) {
      var e = document.createEvent('Event');
      e.initEvent(type, true, true);
      Object.defineProperty(e, 'keyCode', { get: function () { return code; } });
      document.dispatchEvent(e);
    });
  }
  var codes = { Up: 38, Down: 40, Left: 37, Right: 39, Enter: 13, Back: 10009, ChUp: 427, ChDown: 428 };
  var segs = spec.split(';');
  var out = [];
  return new Promise(function (resolve) {
    var si = 0;
    function stats(name) {
      var fm = window.__fm; var f = fm.frames.splice(0); var lt = fm.longTasks.splice(0);
      var s = f.slice().sort(function (a, b) { return a - b; });
      var sum = f.reduce(function (a, b) { return a + b; }, 0);
      out.push(name + ': fps=' + Math.round(1000 / (sum / f.length)) + ' p95=' + Math.round(s[Math.floor(s.length * 0.95)]) + ' max=' + Math.round(s[s.length - 1]) + ' janky=' + f.filter(function (x) { return x > 34; }).length + '/' + f.length + ' longTasks=' + lt.length + ' longest=' + (lt.length ? Math.max.apply(null, lt) : 0));
    }
    function next() {
      if (si >= segs.length) { resolve(out.join('\n')); return; }
      var parts = segs[si++].split('*'); var k = parts[0]; var n = Number(parts[1] || 1);
      window.__fm.frames.splice(0); window.__fm.longTasks.splice(0);
      var i = 0;
      (function step() {
        if (i >= n) { setTimeout(function () { stats(k + 'x' + n); next(); }, 600); return; }
        i++; press(codes[k]); setTimeout(step, gap);
      })();
    }
    next();
  });
})
