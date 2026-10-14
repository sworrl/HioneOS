package com.hione.tools.calc

import com.hione.tools.Mascot
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.tools.ui.GlassDialog
import com.hione.tools.ui.GlassIconButton
import com.hione.tools.ui.GlassSegmented
import com.hione.tools.ui.GlassTextButton
import com.hione.tools.ui.Hione
import com.hione.tools.ui.HioneBackground
import com.hione.tools.ui.HioneMono
import com.hione.tools.ui.HioneTheme
import com.hione.tools.ui.glass
import com.hione.tools.ui.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class CalculatorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val model = CalcModel(applicationContext)
        setContent { HioneTheme { CalculatorScreen(model) } }
    }
}

/** One key press as it appears on screen and as the engine reads it. They differ only for a
 *  carried-over result, which shows 20 digits but keeps its full 60-digit value underneath so
 *  1 ÷ 3 = then × 3 = gives exactly 1. */
data class Key(val show: String, val eval: String = show) {
    val endsValue: Boolean get() = show.last().isDigit() || show.last() in ".)πe!%"
}

data class HistoryEntry(val expr: String, val result: String, val exact: String)

class CalcModel(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("calc", Context.MODE_PRIVATE)

    val keys = mutableStateListOf<Key>()
    var justEvaluated by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var degrees by mutableStateOf(prefs.getBoolean("deg", true))
        private set
    var scientific by mutableStateOf(prefs.getBoolean("sci", false))
        private set
    var inverse by mutableStateOf(false)
    val history = mutableStateListOf<HistoryEntry>().apply { addAll(loadHistory()) }

    fun setDeg(v: Boolean) { degrees = v; prefs.edit().putBoolean("deg", v).apply() }
    fun toggleSci() { scientific = !scientific; prefs.edit().putBoolean("sci", scientific).apply() }

    val expression: String get() = keys.joinToString("") { it.show }
    private val evalString: String get() = keys.joinToString("") { it.eval }

    private fun openParens(): Int = keys.sumOf { k -> k.show.count { it == '(' } - k.show.count { it == ')' } }
    private fun lastEndsValue() = keys.lastOrNull()?.endsValue == true

    /** Snapshot of what the live preview needs, taken on the main thread. */
    fun previewInput(): Triple<String, String, Boolean>? {
        if (keys.isEmpty() || justEvaluated) return null
        val e = expression
        if (e.none { it in "+−×÷^!%√(" || it.isLetter() || it == 'π' }) return null
        return Triple(e, evalString, degrees)
    }

    /** Live answer for the expression so far, or null when incomplete. Series evaluation of
     *  trig and logs at 60 digits can take tens of milliseconds on the M500, so callers run
     *  this off the main thread. */
    fun computePreview(input: Triple<String, String, Boolean>?): String? = input?.let { (_, ev, deg) ->
        runCatching { CalcEngine.format(CalcEngine.evaluate(ev, deg)) }.getOrNull()
    }

    private fun touch() { error = null }

    private fun startFreshIfEvaluated() {
        if (justEvaluated) { keys.clear(); justEvaluated = false }
    }

    fun digit(d: String) {
        touch(); startFreshIfEvaluated()
        keys += Key(d)
    }

    fun dot() {
        touch(); startFreshIfEvaluated()
        // Only one point per number: walk back over the current number's keys.
        for (k in keys.asReversed()) {
            if (k.show == ".") return
            if (!k.show.all { it.isDigit() }) break
        }
        if (keys.lastOrNull()?.show?.last()?.isDigit() != true) keys += Key("0")
        keys += Key(".")
    }

    fun op(o: String) {
        touch()
        justEvaluated = false
        val last = keys.lastOrNull()
        when {
            last == null -> if (o == "−") keys += Key(o)
            last.show in listOf("+", "−", "×", "÷", "^") -> {
                if (o == "−" && last.show in listOf("×", "÷", "^")) keys += Key(o)
                else if (keys.size > 1 || o == "−") keys[keys.lastIndex] = Key(o)
            }
            last.show.endsWith("(") || last.show == "√" -> if (o == "−") keys += Key(o)
            else -> keys += Key(o)
        }
    }

    fun postfix(p: String) {
        touch(); justEvaluated = false
        if (lastEndsValue()) keys += Key(p)
    }

    fun func(name: String) {
        touch(); startFreshIfEvaluated()
        keys += Key("$name(")
        inverse = false
    }

    fun prefix(sym: String) {
        touch(); startFreshIfEvaluated()
        keys += Key(sym)
    }

    fun constant(c: String) {
        touch(); startFreshIfEvaluated()
        keys += Key(c)
    }

    /** One key for both parentheses: close one if a value just ended and one is open. */
    fun paren() {
        touch()
        if (justEvaluated) { justEvaluated = false }
        if (lastEndsValue() && openParens() > 0) keys += Key(")") else keys += Key("(")
    }

    fun backspace() {
        touch()
        val last = keys.lastOrNull() ?: return
        justEvaluated = false
        if (last.eval != last.show) {
            // A carried-over result: break it into editable characters first.
            keys.removeAt(keys.lastIndex)
            last.show.forEach { keys += Key(it.toString()) }
        }
        if (keys.isNotEmpty()) keys.removeAt(keys.lastIndex)
    }

    fun clear() { touch(); keys.clear(); justEvaluated = false }

    fun equals() {
        if (keys.isEmpty()) return
        // Close what the user left open, so "sin(30" works.
        repeat(openParens().coerceAtLeast(0)) { keys += Key(")") }
        val expr = expression
        try {
            val v = CalcEngine.evaluate(evalString, degrees)
            val shown = CalcEngine.format(v)
            val exact = v.round(CalcEngine.MC).toString()
            if (history.firstOrNull()?.expr != expr) {
                history.add(0, HistoryEntry(expr, shown, exact))
                while (history.size > 100) history.removeAt(history.lastIndex)
                saveHistory()
            }
            keys.clear()
            keys += Key(shown, exact)
            justEvaluated = true
            error = null
        } catch (e: CalcEngine.CalcException) {
            error = e.message
        } catch (e: ArithmeticException) {
            error = "Too big"
        }
    }

    fun recall(h: HistoryEntry, asExpression: Boolean) {
        touch()
        keys.clear()
        if (asExpression) keys.addAll(splitKeys(h.expr)) else keys += Key(h.result, h.exact)
        justEvaluated = !asExpression
    }

    fun clearHistory() { history.clear(); saveHistory() }

    /** Last live preview, published by the display. Used by Copy. */
    var lastPreview by mutableStateOf<String?>(null)

    fun currentResultText(): String? = when {
        justEvaluated -> keys.firstOrNull()?.show
        else -> lastPreview
    }

    /** Paste: accept what people actually copy (1,234.5 / 3*4 / -2) and turn it into keys. */
    fun paste(text: String): Boolean {
        val cleaned = text.trim().replace(",", "").replace("−", "−").replace(" ", "")
        if (cleaned.isEmpty()) return false
        val ks = splitKeys(cleaned)
        if (ks.isEmpty()) return false
        touch(); startFreshIfEvaluated()
        keys.addAll(ks)
        return true
    }

    private fun splitKeys(s: String): List<Key> {
        val out = ArrayList<Key>()
        var i = 0
        val norm = s.replace('*', '×').replace('/', '÷').replace('-', '−')
        while (i < norm.length) {
            val fn = listOf("asin(", "acos(", "atan(", "sin(", "cos(", "tan(", "ln(", "log(").firstOrNull { norm.startsWith(it, i) }
            if (fn != null) { out += Key(fn); i += fn.length; continue }
            val c = norm[i]
            if (c.isDigit() || c in ".+−×÷^()%!πe√E") out += Key(c.toString()) else return emptyList()
            i++
        }
        return out
    }

    private fun loadHistory(): List<HistoryEntry> = runCatching {
        val arr = JSONArray(prefs.getString("history", "[]"))
        (0 until arr.length()).map { val o = arr.getJSONObject(it); HistoryEntry(o.getString("e"), o.getString("r"), o.optString("x", o.getString("r"))) }
    }.getOrDefault(emptyList())

    private fun saveHistory() {
        val arr = JSONArray()
        history.forEach { arr.put(JSONObject().put("e", it.expr).put("r", it.result).put("x", it.exact)) }
        prefs.edit().putString("history", arr.toString()).apply()
    }
}

private fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Result", text.replace('−', '-')))
    // Android 13+ shows its own clipboard confirmation; a toast on top of it is noise.
    if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

@Composable
fun CalculatorScreen(m: CalcModel) {
    val ctx = LocalContext.current
    var showHistory by remember { mutableStateOf(false) }

    HioneBackground {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 10.dp)) {
            // ---- top controls
            Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassSegmented(listOf("DEG", "RAD"), if (m.degrees) 0 else 1, { m.setDeg(it == 0) }, Modifier.width(132.dp))
                Spacer(Modifier.weight(1f))
                GlassIconButton(Icons.Outlined.ContentPaste, "Paste", {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                    if (t == null || !m.paste(t)) Toast.makeText(ctx, "Nothing to paste", Toast.LENGTH_SHORT).show()
                })
                Spacer(Modifier.width(8.dp))
                GlassIconButton(Icons.Outlined.ContentCopy, "Copy result", {
                    val r = m.currentResultText() ?: m.expression.takeIf { it.isNotEmpty() }
                    if (r != null) copyText(ctx, r)
                })
                Spacer(Modifier.width(8.dp))
                GlassIconButton(Icons.Outlined.History, "History", { showHistory = true })
            }

            // ---- display
            Display(m, Modifier.fillMaxWidth().weight(if (m.scientific) 0.8f else 1f))

            Spacer(Modifier.height(10.dp))

            // ---- scientific panel
            AnimatedVisibility(m.scientific, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                ScientificPad(m)
            }

            // ---- keypad
            Keypad(m, Modifier.fillMaxWidth().weight(2.2f))
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showHistory) HistoryDialog(m, onDismiss = { showHistory = false })
}

@Composable
private fun Display(m: CalcModel, modifier: Modifier) {
    val ctx = LocalContext.current
    val expr = m.expression
    val input = m.previewInput()
    val preview by produceState<String?>(null, input) {
        value = withContext(Dispatchers.Default) { m.computePreview(input) }
        m.lastPreview = value
    }
    val big by animateFloatAsState(if (m.justEvaluated) 1f else 0f, tween(180), label = "eval")
    val scroll = rememberScrollState()
    LaunchedEffect(expr) { scroll.scrollTo(scroll.maxValue) }

    Column(
        modifier
            .glass(RoundedCornerShape(26.dp))
            .combinedClickable(
                onClick = {},
                onLongClick = { (m.currentResultText() ?: expr.takeIf { it.isNotEmpty() })?.let { copyText(ctx, it) } }
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.End
    ) {
        // Shrink long expressions instead of wrapping them; past that, it scrolls.
        val len = expr.length
        val exprSize = when {
            m.justEvaluated -> 52 - (len / 3).coerceAtMost(26)
            len < 10 -> 46
            len < 14 -> 38
            len < 18 -> 32
            else -> 26
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(scroll), horizontalArrangement = Arrangement.End) {
            Text(
                if (expr.isEmpty()) "0" else expr,
                color = if (m.justEvaluated) Hione.TealGlow else Hione.Text,
                fontSize = (exprSize + 6 * big).sp,
                fontFamily = HioneMono,
                fontWeight = FontWeight.Light,
                maxLines = 1,
                softWrap = false
            )
        }
        Spacer(Modifier.height(6.dp))
        val sub = when {
            m.error != null -> m.error!!
            preview != null && input != null -> "= " + CalcEngine.group(preview!!)
            m.justEvaluated -> m.history.firstOrNull()?.expr?.let { "$it =" } ?: ""
            else -> ""
        }
        Text(
            sub,
            color = if (m.error != null) Hione.PinkSoft else Hione.Muted,
            fontSize = 22.sp,
            fontFamily = HioneMono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private enum class KeyKind { NUM, OP, FN, EQ, CLEAR }

@Composable
private fun CalcKey(
    label: String,
    kind: KeyKind,
    modifier: Modifier,
    onLongClick: (() -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val bg: Modifier = when (kind) {
        KeyKind.EQ -> Modifier.clip(shape)
            .background(Brush.verticalGradient(listOf(Hione.PinkSoft, Hione.Pink.copy(alpha = 0.75f))))
            .border(1.dp, Color.White.copy(alpha = 0.25f), shape)
        KeyKind.OP -> Modifier.glass(shape, tint = Hione.Teal.copy(alpha = 0.16f))
        KeyKind.CLEAR -> Modifier.glass(shape, accent = Hione.PinkSoft, tint = Hione.Pink.copy(alpha = 0.10f))
        KeyKind.FN -> Modifier.glass(shape, tint = Mascot.color(0x660B1519), rimAlpha = 0.25f)
        KeyKind.NUM -> Modifier.glass(shape)
    }
    Box(
        modifier.padding(4.dp).pressable(onClick, onLongClick).then(bg),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) icon() else Text(
            label,
            color = when (kind) {
                KeyKind.EQ -> Color.White
                KeyKind.OP -> Hione.TealGlow
                KeyKind.CLEAR -> Hione.PinkSoft
                KeyKind.FN -> Hione.TextDim
                KeyKind.NUM -> Hione.Text
            },
            fontSize = when (kind) { KeyKind.FN -> 17.sp; KeyKind.NUM -> 28.sp; else -> 26.sp },
            fontWeight = if (kind == KeyKind.NUM) FontWeight.Normal else FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun ScientificPad(m: CalcModel) {
    val inv = m.inverse
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        val rowMod = Modifier.fillMaxWidth().height(50.dp)
        Row(rowMod) {
            CalcKey(if (inv) "INV" else "inv", KeyKind.FN, Modifier.weight(1f).fillMaxHeight()) { m.inverse = !m.inverse }
            CalcKey(if (inv) "sin⁻¹" else "sin", KeyKind.FN, Modifier.weight(1f).fillMaxHeight()) { m.func(if (inv) "asin" else "sin") }
            CalcKey(if (inv) "cos⁻¹" else "cos", KeyKind.FN, Modifier.weight(1f).fillMaxHeight()) { m.func(if (inv) "acos" else "cos") }
            CalcKey(if (inv) "tan⁻¹" else "tan", KeyKind.FN, Modifier.weight(1f).fillMaxHeight()) { m.func(if (inv) "atan" else "tan") }
            CalcKey("π", KeyKind.FN, Modifier.weight(1f).fillMaxHeight()) { m.constant("π") }
        }
        Row(rowMod) {
            CalcKey("ln", KeyKind.FN, Modifier.weight(1f).fillMaxHeight()) { m.func("ln") }
            CalcKey("log", KeyKind.FN, Modifier.weight(1f).fillMaxHeight()) { m.func("log") }
            CalcKey("√", KeyKind.FN, Modifier.weight(1f).fillMaxHeight()) { m.prefix("√") }
            CalcKey("xʸ", KeyKind.FN, Modifier.weight(1f).fillMaxHeight()) { m.op("^") }
            CalcKey("e", KeyKind.FN, Modifier.weight(1f).fillMaxHeight()) { m.constant("e") }
        }
        Row(rowMod) {
            CalcKey("(", KeyKind.FN, Modifier.weight(1f).fillMaxHeight()) { m.prefix("(") }
            CalcKey(")", KeyKind.FN, Modifier.weight(1f).fillMaxHeight()) { m.postfix(")") }
            CalcKey("x!", KeyKind.FN, Modifier.weight(1f).fillMaxHeight()) { m.postfix("!") }
            CalcKey("x²", KeyKind.FN, Modifier.weight(1f).fillMaxHeight()) { m.op("^"); m.digit("2") }
            CalcKey("1/x", KeyKind.FN, Modifier.weight(1f).fillMaxHeight()) { m.op("^"); m.op("−"); m.digit("1") }
        }
    }
}

@Composable
private fun Keypad(m: CalcModel, modifier: Modifier) {
    Column(modifier) {
        val rows: List<List<Pair<String, KeyKind>>> = listOf(
            listOf("AC" to KeyKind.CLEAR, "( )" to KeyKind.OP, "%" to KeyKind.OP, "÷" to KeyKind.OP),
            listOf("7" to KeyKind.NUM, "8" to KeyKind.NUM, "9" to KeyKind.NUM, "×" to KeyKind.OP),
            listOf("4" to KeyKind.NUM, "5" to KeyKind.NUM, "6" to KeyKind.NUM, "−" to KeyKind.OP),
            listOf("1" to KeyKind.NUM, "2" to KeyKind.NUM, "3" to KeyKind.NUM, "+" to KeyKind.OP),
            listOf("sci" to KeyKind.FN, "0" to KeyKind.NUM, "." to KeyKind.NUM, "=" to KeyKind.EQ),
        )
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                row.forEach { (label, kind) ->
                    val km = Modifier.weight(1f).fillMaxHeight()
                    when (label) {
                        "AC" -> CalcKey(if (m.keys.isEmpty() || m.justEvaluated) "AC" else "⌫", kind, km,
                            onLongClick = { m.clear() },
                            icon = if (m.keys.isEmpty() || m.justEvaluated) null else ({
                                Icon(Icons.AutoMirrored.Outlined.Backspace, "Delete", tint = Hione.PinkSoft, modifier = Modifier.size(28.dp))
                            })
                        ) { if (m.keys.isEmpty() || m.justEvaluated) m.clear() else m.backspace() }
                        "( )" -> CalcKey(label, kind, km) { m.paren() }
                        "%" -> CalcKey(label, kind, km) { m.postfix("%") }
                        "÷", "×", "−", "+" -> CalcKey(label, kind, km) { m.op(label) }
                        "." -> CalcKey(label, kind, km) { m.dot() }
                        "=" -> CalcKey(label, kind, km) { m.equals() }
                        "sci" -> CalcKey(label, kind, km, icon = {
                            Icon(if (m.scientific) Icons.Outlined.ExpandLess else Icons.Outlined.Functions, "Scientific", tint = Hione.TealGlow, modifier = Modifier.size(28.dp))
                        }) { m.toggleSci() }
                        else -> CalcKey(label, kind, km) { m.digit(label) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryDialog(m: CalcModel, onDismiss: () -> Unit) {
    GlassDialog(onDismiss, title = "History", scrollable = false, buttons = {
        if (m.history.isNotEmpty()) GlassTextButton("Clear", { m.clearHistory() }, accent = Hione.PinkSoft)
        GlassTextButton("Close", onDismiss)
    }) {
        if (m.history.isEmpty()) {
            Text("Nothing here yet. Results show up here after you press =.", color = Hione.Muted, fontSize = 15.sp)
        } else {
            Text("Tap to use the result. Hold to edit the expression.", color = Hione.Muted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxWidth().height(380.dp)) {
                items(m.history) { h ->
                    Column(
                        Modifier.fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .glass(RoundedCornerShape(14.dp), rimAlpha = 0.2f)
                            .combinedClickable(
                                onClick = { m.recall(h, asExpression = false); onDismiss() },
                                onLongClick = { m.recall(h, asExpression = true); onDismiss() }
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(h.expr, color = Hione.Muted, fontSize = 15.sp, fontFamily = HioneMono, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("= " + CalcEngine.group(h.result), color = Hione.TealGlow, fontSize = 21.sp, fontFamily = HioneMono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
