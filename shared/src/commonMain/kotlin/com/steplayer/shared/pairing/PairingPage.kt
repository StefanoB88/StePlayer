package com.steplayer.shared.pairing

import com.steplayer.shared.model.SourceInput

/** The self-contained mobile web page served to the phone (no scripts, no external resources). */
internal object PairingPage {

    fun toInput(f: Map<String, String>): SourceInput? {
        val name = f["name"].orEmpty().trim()
        val ua = f["ua"]?.trim()?.takeIf { it.isNotEmpty() }
        return when (f["type"]) {
            "xtream" -> {
                val server = f["server"].orEmpty().trim()
                val user = f["username"].orEmpty().trim()
                val pass = f["password"].orEmpty()
                if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) null
                else SourceInput.Xtream(name, server, user, pass, ua)
            }
            "m3u" -> {
                val url = f["url"].orEmpty().trim()
                if (url.isEmpty()) null else SourceInput.M3u(name, url, f["epg"]?.trim()?.takeIf { it.isNotEmpty() }, ua)
            }
            else -> null
        }
    }

    private fun esc(s: String?) = s.orEmpty()
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private const val STYLE = """
:root{color-scheme:dark}*{box-sizing:border-box}
body{margin:0;font-family:-apple-system,system-ui,Roboto,sans-serif;background:#0B0D12;color:#F5F7FA;padding:24px 16px}
.card{max-width:480px;margin:0 auto;background:#141821;border:1px solid #2A3142;border-radius:16px;padding:24px}
h1{font-size:24px;margin:0 0 4px}.sub{color:#9AA3B2;margin:0 0 20px;font-size:15px}
.logo{width:44px;height:44px;border-radius:12px;background:linear-gradient(135deg,#7C5CFF,#00D4FF);display:grid;place-items:center;margin-bottom:16px}
.logo:after{content:"";border-left:14px solid #fff;border-top:9px solid transparent;border-bottom:9px solid transparent;margin-left:4px}
.tabs{display:flex;background:#0B0D12;border-radius:999px;padding:4px;margin-bottom:20px}
.tabs label{flex:1;text-align:center;padding:10px;border-radius:999px;color:#9AA3B2;font-weight:600;font-size:15px}
input[type=radio]{display:none}
#t-x:checked~.tabs label[for=t-x],#t-m:checked~.tabs label[for=t-m]{background:#1C2130;color:#F5F7FA}
.pane{display:none}#t-x:checked~.px,#t-m:checked~.pm{display:block}
label.f{display:block;font-size:13px;color:#9AA3B2;margin:14px 0 6px}
input.i{width:100%;padding:14px;border-radius:12px;border:1px solid #2A3142;background:#0B0D12;color:#F5F7FA;font-size:16px}
input.i:focus{outline:2px solid #7C5CFF;border-color:transparent}
button{width:100%;margin-top:24px;padding:16px;border:0;border-radius:14px;font-size:17px;font-weight:700;color:#fff;background:linear-gradient(90deg,#7C5CFF,#00D4FF)}
.err{background:#3a1520;border:1px solid #FF3B5C;color:#ffd0d8;padding:12px;border-radius:12px;margin-bottom:16px;font-size:14px}
.note{color:#9AA3B2;font-size:12px;margin-top:18px;line-height:1.5}
"""

    fun form(action: String, error: String? = null, previous: SourceInput? = null): String {
        val x = previous as? SourceInput.Xtream
        val m = previous as? SourceInput.M3u
        val m3uSelected = m != null
        return page(
            """
<div class="logo"></div>
<h1>Aggiungi lista alla TV</h1>
<p class="sub">Inserisci i dati forniti dal tuo provider. Vengono inviati solo alla tua TV, sulla rete di casa.</p>
${error?.let { "<div class=\"err\">${esc(it)}</div>" } ?: ""}
<form method="post" action="${esc(action)}" autocomplete="off">
<input type="radio" name="type" value="xtream" id="t-x" ${if (!m3uSelected) "checked" else ""}>
<input type="radio" name="type" value="m3u" id="t-m" ${if (m3uSelected) "checked" else ""}>
<div class="tabs"><label for="t-x">Accesso al server</label><label for="t-m">Lista M3U</label></div>
<label class="f" for="name">Nome (facoltativo)</label>
<input class="i" id="name" name="name" placeholder="La mia lista" value="${esc(previous?.name)}">
<div class="pane px">
<label class="f" for="server">Server</label>
<input class="i" id="server" name="server" inputmode="url" autocapitalize="none" placeholder="http://server.esempio:8080" value="${esc(x?.serverUrl)}">
<label class="f" for="username">Username</label>
<input class="i" id="username" name="username" autocapitalize="none" value="${esc(x?.username)}">
<label class="f" for="password">Password</label>
<input class="i" id="password" name="password" type="password" autocapitalize="none">
</div>
<div class="pane pm">
<label class="f" for="url">URL lista</label>
<input class="i" id="url" name="url" inputmode="url" autocapitalize="none" placeholder="https://server.esempio/lista.m3u" value="${esc(m?.listUrl)}">
<label class="f" for="epg">URL guida TV (facoltativo)</label>
<input class="i" id="epg" name="epg" inputmode="url" autocapitalize="none" value="${esc(m?.epgUrl)}">
</div>
<button type="submit">Invia alla TV</button>
</form>
<p class="note">StePlayer è solo un lettore e non fornisce contenuti. Usa solo liste che hai il diritto di guardare.
Questa pagina è servita direttamente dalla tua TV e smette di funzionare dopo l'uso o entro 10 minuti.</p>
""",
        )
    }

    fun message(title: String, text: String): String = page(
        """<div class="logo"></div><h1>${esc(title)}</h1><p class="sub">${esc(text)}</p>""",
    )

    private fun page(content: String) = """<!doctype html><html lang="it"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex">
<title>StePlayer</title><style>$STYLE</style></head><body><main class="card">$content</main></body></html>"""
}
