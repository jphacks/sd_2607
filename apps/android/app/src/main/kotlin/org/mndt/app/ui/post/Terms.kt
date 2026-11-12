package org.mndt.app.ui.post

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.edit
import org.mndt.app.ui.theme.Mosimo

const val CONTACT_ADDRESS = "contact@mosimo.jisaku.dev"
private const val TERMS_ACCEPTED = "termsAccepted"

private var termsAccepted: MutableState<Boolean>? = null

/**
 * Whether the terms were agreed, in the same "app" preferences as the timeline's read marker.
 * One state for the whole process, so agreeing in [TermsAgreement] also updates the screen that showed it.
 */
@Composable
fun rememberTermsAccepted(): MutableState<Boolean> {
    val context = LocalContext.current.applicationContext
    return remember {
        termsAccepted ?: run {
            val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
            val state = mutableStateOf(prefs.getBoolean(TERMS_ACCEPTED, false))
            object : MutableState<Boolean> by state {
                override var value: Boolean
                    get() = state.value
                    set(v) { prefs.edit { putBoolean(TERMS_ACCEPTED, v) }; state.value = v }
            }.also { termsAccepted = it }
        }
    }
}

/** Shown before the first post, annotations included: agreed terms with zero tolerance for objectionable content. */
@Composable
fun TermsAgreement(onClose: () -> Unit, onAgree: () -> Unit) {
    var accepted by rememberTermsAccepted()
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("利用規約への同意", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = Mosimo.textPrimary)
            IconButton(onClose) { Icon(Icons.Filled.Close, "閉じる", tint = Mosimo.textSecondary) }
        }
        Text("投稿するには、次の利用規約への同意が必要です。", style = MaterialTheme.typography.bodySmall, color = Mosimo.textSecondary)
        Box(Modifier.weight(1f, fill = false).heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) { TermsText() }
        Button({ accepted = true; onAgree() }, Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Mosimo.primary, contentColor = Mosimo.onPrimary)) { Text("同意して続ける") }
    }
}

/** Runs the wrapped action now, or once the terms are agreed in a dialog. */
@Composable
fun rememberTermsGate(): (() -> Unit) -> Unit {
    val accepted by rememberTermsAccepted()
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    pending?.let { run ->
        Dialog(onDismissRequest = { pending = null }) {
            Surface(color = Mosimo.bgSurface, shape = MaterialTheme.shapes.large) {
                TermsAgreement(onClose = { pending = null }, onAgree = { pending = null; run() })
            }
        }
    }
    return { run -> if (accepted) run() else pending = run }
}

@Composable
fun TermsText(modifier: Modifier = Modifier) = LegalText(TERMS, modifier)

@Composable
fun PolicyText(modifier: Modifier = Modifier) = LegalText(PRIVACY_POLICY, modifier)

/** Plain text with `**bold**` spans, as shared with the iOS app. */
@Composable
private fun LegalText(source: String, modifier: Modifier) {
    val text = remember(source) {
        buildAnnotatedString {
            source.lines().forEachIndexed { i, line ->
                if (i > 0) append("\n")
                var rest = line
                while (true) {
                    val start = rest.indexOf("**")
                    val end = if (start >= 0) rest.indexOf("**", start + 2) else -1
                    if (start < 0 || end < 0) { append(rest); break }
                    append(rest.substring(0, start))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(rest.substring(start + 2, end)) }
                    rest = rest.substring(end + 2)
                }
            }
        }
    }
    SelectionContainer(modifier) { Text(text, style = MaterialTheme.typography.bodyMedium, color = Mosimo.textPrimary) }
}

// Kept identical to the iOS app (apps/ios/Mosimo/Terms.swift).
private val TERMS = """
**「もしもの防災」利用規約**
制定日：2026年11月11日
最終改定日：2026年11月13日

Team JISAKU（以下「当団体」といいます。）は、当団体が提供するアプリケーション「もしもの防災」（以下「本アプリ」といいます。）の利用条件を、以下のとおり定めます。

**第1条（適用）**
1. 本規約は、本アプリの利用に関する当団体と利用者との間の一切の関係に適用されます。
2. 利用者は、本規約に同意したうえで本アプリを利用するものとします。

**第2条（目的）**
本アプリは、災害時における被害、避難その他の状況に関する情報を利用者の間で共有することを目的とします。利用者は、この目的の範囲内で本アプリを利用するものとします。

**第3条（定義）**
本規約において、次の各号に掲げる用語の意義は、当該各号に定めるところによります。
1. 「投稿」とは、利用者が本アプリを通じて発信する情報（他の投稿に付随して発信する情報を含みます。）をいいます。
2. 「中継者」とは、投稿その他の情報を受信、保存又は転送する端末、アプリケーション及びサーバー（当団体以外の者が運営するものを含みます。）をいいます。

**第4条（投稿の公開）**
1. 投稿は、これに付された位置その他の情報とともに、暗号化されることなく公開されます。
2. 投稿は、中継者により受信、保存及び転送され、中継者及びこれに接続する者を含む何人も閲覧することができます。
3. 利用者は、投稿その他本アプリが送信する情報により、利用者又はその所在が特定されるおそれがあることを了承するものとします。

**第5条（禁止事項）**
利用者は、本アプリの利用にあたり、次の各号に該当する行為をしてはなりません。
1. 法令又は公序良俗に違反する行為
2. わいせつ、暴力的、差別的その他不快な内容を投稿する行為
3. 他人を誹謗中傷し、又は他人に対して嫌がらせ若しくは脅迫を行う行為
4. 事実と異なる災害又は避難に関する情報を故意に投稿する行為
5. 他人を特定することができる情報を投稿する行為
6. 他人、公的機関その他の団体になりすます行為
7. 宣伝、勧誘、同一内容の反復その他本アプリの目的に関係のない投稿を行う行為
8. 本アプリ又は中継者の運営を妨げる行為
9. 正当な投稿の流通を妨げる目的で報告を行う行為、又は事実と異なる報告を行う行為
10. その他当団体が不適切と判断する行為

**第6条（報告及び利用者による措置）**
1. 当団体は、不適切な投稿及び迷惑な利用を一切容認しません。
2. 利用者は、本アプリ内から、不適切な投稿を報告することができます。報告は、公開の情報として中継者に送信されます。
3. 報告を受け入れるか否か、及び報告に係る投稿をどのように取り扱うかは、報告を受け取った各中継者が判断します。
4. 当団体は、当団体が運営するサーバーに到達した報告を受け取り次第確認し、本規約に違反すると判断した場合には、当該投稿又は当該利用者による投稿の配信の停止その他必要な措置を講じます。
5. 利用者は、本アプリ内から、特定の利用者の投稿を表示及び転送しないよう設定することができます。

**第7条（投稿の削除）**
1. 利用者は、本アプリ内から、自己の投稿の削除を依頼することができます。削除の依頼は、中継者に送信されます。
2. 削除の依頼に応じるか否かは、依頼を受け取った各中継者が判断します。当団体は、投稿が削除されることを保証しません。
3. 利用者は、公開した投稿を完全に削除できない場合があることを了承のうえ、投稿するものとします。

**第8条（免責）**
1. 投稿の内容については、当該投稿を行った利用者が責任を負います。当団体は、投稿の正確性、完全性及び有用性を保証しません。
2. 当団体は、投稿その他の情報が到達すること及びその到達の時期を保証しません。
3. 利用者は、生命又は身体に関わる判断を、公的機関の発表及び周囲の状況に基づき、自らの責任において行うものとします。
4. 当団体は、本アプリの利用により利用者に生じた損害について、当団体の故意又は重大な過失による場合を除き、責任を負いません。

**第9条（本規約の変更）**
当団体は、必要と判断した場合には、本規約を変更することができます。変更後の本規約は、当団体のウェブサイト及び本アプリ内に掲示した時点から効力を生じるものとし、変更後に本アプリを利用した利用者は、変更後の本規約に同意したものとみなします。

**第10条（準拠法）**
本規約は、日本法に準拠し、日本法に従って解釈されるものとします。

**運営者：** Team JISAKU
**お問い合わせ：** $CONTACT_ADDRESS
""".trimIndent()

// Articles and the revision date are shared by every app and hp/src/content/policy.md; only the name and enactment date differ.
private val PRIVACY_POLICY = """
**「もしもの防災」プライバシーポリシー**
制定日：2026年11月2日
最終改定日：2026年11月13日

Team JISAKU（以下「当団体」といいます。）は、当団体が提供するアプリケーション「もしもの防災」（以下「本アプリ」といいます。）における利用者の情報の取扱いについて、以下のとおりプライバシーポリシー（以下「本ポリシー」といいます。）を定めます。

**第1条（定義）**
本ポリシーにおいて、次の各号に掲げる用語の意義は、当該各号に定めるところによります。
1. 「発信情報」とは、利用者が本アプリを通じて送信する情報及びこれに付された情報をいいます。
2. 「識別子」とは、利用者を他の利用者と区別するために本アプリが生成する情報をいいます。
3. 「中継者」とは、発信情報その他の情報を受信、保存又は転送する端末、アプリケーション及びサーバー（当団体以外の者が運営するものを含みます。）をいいます。

**第2条（取り扱う情報）**
本アプリは、本アプリの提供に必要な範囲で、次の各号に掲げる情報を取り扱います。
1. 発信情報の内容及び日時
2. 識別子及び発信情報の真正性を確認するための情報
3. 他の端末との通信に必要な情報
4. 利用者が発信情報に付すことを選択した位置情報

**第3条（利用状況）**
1. 本アプリの利用にあたり、氏名、連絡先その他特定の個人を識別する情報の登録は必要ありません。
2. 当団体は、本アプリの改善及び統計情報の作成のため、本アプリの利用状況に関する情報（おおまかな地域を含みます。）を取得することがあります。当団体は、この情報を識別子と結び付けずに取り扱います。
3. 当団体は、前項の情報から特定の個人を識別することができないように加工した統計情報を作成し、公表することがあります。
4. 利用者は、本アプリの設定により、第2項の情報の送信を停止することができます。
5. 当団体は、本アプリにおいて、広告の配信又は利用者の追跡を目的とする情報を取得しません。

**第4条（情報の公開及び送信）**
1. 発信情報は、中継者により受信、保存及び転送され、中継者及びこれに接続する者を含む何人もこれを取得することができます。
2. 発信情報のうち公開を目的とするものは、暗号化されることなく公開されます。特定の相手に宛てて暗号化される発信情報であっても、送信者及び宛先の識別子は公開されます。
3. 本アプリは、通信のため、利用者が発信情報を送信しない場合であっても、他の端末に識別子を送信することがあります。
4. 中継者その他通信を傍受した者は、端末の通信上の識別情報その他の接続に関する情報を知り得る場合があります。
5. 識別子は、その登録及び照会のため、外部のサービスに送信されることがあります。

**第5条（位置情報）**
1. 本アプリは、利用者が明示的に選択した場合に限り、位置情報を発信情報に付します。
2. 発信情報に付された位置情報は、発信情報とともに公開され、これにより利用者又はその所在が特定されるおそれがあります。

**第6条（情報の保存及び削除）**
1. 本アプリは、発信情報、識別子に係る秘密の情報その他の情報を利用者の端末内に暗号化して保存し、端末のバックアップの対象としません。識別子に係る秘密の情報は、端末の外部に送信しません。
2. 利用者は、本アプリの削除又は本アプリのデータの消去により、端末内の情報を削除することができます。この場合、利用者は、同一の識別子により発信することができなくなります。
3. 本アプリが発信情報の削除を依頼する機能を提供する場合、利用者は、これにより自己の発信情報の削除を依頼することができます。削除の依頼に応じるか否かは各中継者が判断し、当団体は、発信情報が削除されることを保証しません。

**第7条（本ポリシーの変更）**
当団体は、必要と判断した場合には、本ポリシーを変更することができます。変更後の本ポリシーは、当団体のウェブサイト及び本アプリ内に掲示した時点から効力を生じるものとし、変更後に本アプリを利用した利用者は、変更後の本ポリシーに同意したものとみなします。

**運営者：** Team JISAKU
**お問い合わせ：** $CONTACT_ADDRESS
""".trimIndent()
