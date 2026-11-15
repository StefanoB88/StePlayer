// Time from page start to: navigation rail, Home rows with posters (dev measurement).
new Promise(function (resolve) {
  var marks = {};
  var t = setInterval(function () {
    var now = Math.round(performance.now());
    if (!marks.rail && document.querySelector('.rail')) marks.rail = now;
    if (!marks.rows && document.querySelector('.hrow')) marks.rows = now;
    if (!marks.posters && document.querySelector('.hrow img')) marks.posters = now;
    if (marks.posters) { clearInterval(t); resolve(marks); }
  }, 30);
  setTimeout(function () { clearInterval(t); resolve(marks); }, 30000);
})
