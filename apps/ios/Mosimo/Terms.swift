import SwiftUI

let policyURL = URL(string: "https://mosimo.jisaku.dev/reference/mobile/policy/")!
let contactAddress = "contact@mosimo.jisaku.dev"
let reportAddress = "report@mosimo.jisaku.dev"

/// Shown before the first post, annotations included (App Review 1.2: agreed terms with zero tolerance for objectionable content).
struct TermsAgreement: View {
    let onClose: () -> Void
    var onAgree: () -> Void = {}
    @AppStorage("termsAccepted") private var accepted = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("利用規約への同意").font(.titleLarge).foregroundStyle(Mosimo.textPrimary)
                Spacer()
                Button(action: onClose) { Image(systemName: "xmark").foregroundStyle(Mosimo.textSecondary).frame(width: 44, height: 44) }
                    .accessibilityLabel("閉じる")
            }
            Text("投稿するには、次の利用規約への同意が必要です。").font(.bodySmall).foregroundStyle(Mosimo.textSecondary)
            ScrollView { TermsText().padding(.vertical, 12) }
            Button { accepted = true; onAgree() } label: {
                Text("同意して続ける").font(.labelLarge).foregroundStyle(Mosimo.onPrimary).frame(maxWidth: .infinity).frame(height: 52)
                    .background(Mosimo.primary, in: RoundedRectangle(cornerRadius: 26))
            }
            .accessibilityIdentifier("terms.agree")
        }
        .padding(16)
        .background(Mosimo.bgSurface.ignoresSafeArea())
    }
}

struct TermsText: View {
    var body: some View {
        Text(LocalizedStringKey(terms)).font(.bodyMedium).foregroundStyle(Mosimo.textPrimary).tint(Mosimo.info)
            .textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
    }
}

// §2 and §6 follow the published privacy policy; the rest is ios-assets/legal/terms-of-use.md.
private let terms = """
**「もしもの防災」利用規約**
制定日：2026年11月11日

Team JISAKU（以下「本団体」）は、本団体が提供するアプリ「もしもの防災」（以下「本アプリ」）の利用条件を以下のとおり定めます。利用者は、本規約に同意したうえで本アプリを利用するものとします。

**1. 本アプリの目的**
本アプリは、災害時に被害や避難の状況を近くの人と共有するためのものです。この目的に沿わない利用はご遠慮ください。

**2. 投稿の公開**
本アプリの投稿（確認・補足・訂正を含みます）は、暗号化されず、投稿した位置とあわせて誰もが閲覧可能な形で公開されます。
投稿はBluetoothやWi-Fi等を通じて周辺端末、運営者の提供するサーバーおよび第三者の提供するサーバー（以下、まとめて「中継ノード」）に中継・保存されます。また、公開識別子等は外部のPLCディレクトリに登録・照会される場合があります。投稿は中継ノードや中継ノードに接続可能なユーザーを含むだれもが閲覧できます。
これらの情報から利用者本人や居場所が特定される場合があります。中継ノードに送信した投稿を完全に削除することはできません。

**3. 禁止事項**
利用者は、次の行為をしてはなりません。
1. 法令または公序良俗に反する行為
2. わいせつ、暴力的、差別的、その他不快な内容の投稿
3. 他人への誹謗中傷、嫌がらせ、脅迫、いじめ
4. 事実と異なる災害情報や避難情報を、故意に投稿すること
5. 氏名、住所、電話番号、顔写真など、他人を特定できる情報の投稿
6. 他人、行政機関、その他の団体になりすます行為
7. 宣伝、勧誘、同じ内容の繰り返しなど、本アプリの目的に関係のない投稿
8. 本アプリやサーバーの運営を妨げる行為

**4. 不適切なコンテンツと利用者への対応**
1. 本団体は、不適切なコンテンツや迷惑な利用者を**一切容認しません**。
2. 利用者は、投稿の詳細画面の「不適切な投稿を報告」または下記の報告先から、不適切な投稿を本団体に知らせることができます。
3. 本団体は、報告を受けてから**24時間以内**に内容を確認します。本規約に違反すると判断した場合は、本団体が運営するサーバーからの削除、アプリ上での非表示、その投稿者の投稿すべての非表示などの措置をとります。
4. 利用者は、アプリ上で特定の投稿者をブロックし、その投稿者の投稿を非表示にできます。

**5. 免責**
1. 投稿の内容は投稿者の責任によるものです。本団体は、投稿内容の正確性、完全性、有用性を保証しません。電子署名は投稿者の同一性を示すもので、内容が事実であることを保証するものではありません。
2. 通信環境や端末の状態によって、投稿が届かないこと、遅れて届くことがあります。
3. 避難など命や身体に関わる判断は、自治体や気象庁などの公式発表と周囲の状況をもとに、利用者自身が行ってください。
4. 本団体は、本アプリの利用によって利用者に生じた損害について、本団体に故意または重大な過失がある場合を除き、責任を負いません。

**6. 改定**
本規約は、機能や情報の取り扱いの変更に応じて改定することがあります。改定はwebサイト上およびアプリ内への掲載をもって効力を生じるものとします。ユーザーは、変更後に本サービスを利用した場合には、当該変更に同意したものとみなされます。

**7. 準拠法**
本規約は日本法に準拠します。

**運営者：** Team JISAKU
**プライバシーポリシー：** [\(policyURL.absoluteString)](\(policyURL.absoluteString))
**お問い合わせ：** [\(contactAddress)](mailto:\(contactAddress))
**不適切な投稿の報告：** [\(reportAddress)](mailto:\(reportAddress))
"""
