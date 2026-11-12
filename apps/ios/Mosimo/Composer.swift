import SwiftUI

private let maxDetail = 140

/// 「状況を伝える」 sheet (Figma 5:410). Location is required here even though the protocol keeps it optional.
struct Composer: View {
    @ObservedObject var vm: MapModel
    let onPick: () -> Void
    @EnvironmentObject private var mesh: MeshCoordinator
    @EnvironmentObject private var location: LocationRepository
    @AppStorage("termsAccepted") private var termsAccepted = false

    var body: some View {
        Group {
            if termsAccepted { form } else { TermsAgreement(onClose: { vm.composing = false }) }
        }
        .background(Mosimo.bgSurface.ignoresSafeArea())
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    @ViewBuilder private var form: some View {
        let fix = location.location
        let point = vm.picked ?? fix?.coordinate
        let fromFix = vm.picked == nil && fix != nil
        let online = mesh.state.network && mesh.state.onlineActive
        let ready = vm.category != nil && point != nil
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text("状況を伝える").font(.titleLarge).foregroundStyle(Mosimo.textPrimary)
                    Spacer()
                    Button { vm.composing = false } label: { Image(systemName: "xmark").foregroundStyle(Mosimo.textSecondary).frame(width: 44, height: 44) }
                        .accessibilityLabel("閉じる")
                }
                HStack(spacing: 12) {
                    Image(systemName: "mappin.circle.fill").foregroundStyle(Mosimo.info)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(point == nil ? "位置が未設定です"
                             : fromFix ? "\(location.address ?? coordsLabel(point!.latitude, point!.longitude)) 付近"
                             : coordsLabel(point!.latitude, point!.longitude))
                            .font(.titleMedium).foregroundStyle(Mosimo.textPrimary)
                        Text(point == nil ? "地図をタップして投稿する位置を指定してください"
                             : fromFix ? (["GPS"] + (fix!.horizontalAccuracy >= 0 ? ["精度 \(accuracyLabel(fix!.horizontalAccuracy))"] : []) + ["この位置がそのまま付きます"]).joined(separator: "・")
                             : "地図で指定した位置・この地点の周辺（範囲）として付きます")
                            .font(.bodySmall).foregroundStyle(Mosimo.textSecondary)
                    }
                    Spacer(minLength: 4)
                    Button(point == nil ? "地図で指定" : "変更", action: onPick).font(.labelLarge).foregroundStyle(Mosimo.info)
                        .accessibilityIdentifier("compose.pick")
                }
                .padding(12).background(Mosimo.bgSurface2, in: RoundedRectangle(cornerRadius: 12))

                label("状況の種類")
                FlowLayout(spacing: 8) {
                    ForEach(Category.allCases) { c in
                        MosimoChip(label: c.label, selected: vm.category == c) { vm.category = c }.accessibilityIdentifier("category.\(c.label)")
                    }
                }
                label("詳しく（任意）")
                VStack(alignment: .leading, spacing: 4) {
                    TextField("例: 遊歩道が冠水。膝下まで水が来ています", text: Binding(get: { vm.detail }, set: { vm.detail = String($0.prefix(maxDetail)) }), axis: .vertical)
                        .lineLimit(3...6).font(.bodyLarge).foregroundStyle(Mosimo.textPrimary)
                        .padding(12).overlay(RoundedRectangle(cornerRadius: 8).stroke(Mosimo.borderStrong))
                        .accessibilityIdentifier("compose.detail")
                    HStack {
                        Text("本文のみでも、どのアプリでも読めます")
                        Spacer()
                        Text("\(vm.detail.count) / \(maxDetail)")
                    }
                    .font(.bodySmall).foregroundStyle(Mosimo.textSecondary)
                }
                label("範囲")
                HStack(spacing: 8) {
                    ForEach([(0, "なし"), (50, "50m"), (200, "200m"), (1000, "1km")], id: \.0) { m, l in
                        MosimoChip(label: l, selected: vm.radius == m) { vm.radius = m }
                    }
                }
                Menu {
                    ForEach([1, 3, 6, 12, 24], id: \.self) { h in Button("\(h)時間") { vm.hours = h } }
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "clock").foregroundStyle(Mosimo.textSecondary)
                        Text("情報の有効期限 \(vm.hours)時間").foregroundStyle(Mosimo.textPrimary)
                        Image(systemName: "chevron.down").font(.system(size: 11)).foregroundStyle(Mosimo.textSecondary)
                    }
                    .font(.labelLarge).padding(.horizontal, 16).frame(height: 40)
                    .overlay(Capsule().stroke(Mosimo.borderStrong))
                }
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "arrow.triangle.2.circlepath").foregroundStyle(Mosimo.info)
                    Text(online ? "オンラインで公開し、近くの端末へも中継して届けます。"
                         : "インターネット圏外のため、近くの端末へ中継して届けます。接続が戻り次第、自動でオンラインにも公開されます。")
                        .font(.bodyMedium).foregroundStyle(Mosimo.info)
                }
                .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                .background(Mosimo.info.opacity(0.14), in: RoundedRectangle(cornerRadius: 12))

                Button { vm.publish(mesh, fix: fix, address: location.address) } label: {
                    HStack(spacing: 8) {
                        if vm.sending { ProgressView().tint(Mosimo.onPrimary); Text("送信中…") }
                        else { Image(systemName: "paperplane.fill"); Text("この場所の状況を投稿する") }
                    }
                    .font(.labelLarge).foregroundStyle(Mosimo.onPrimary).frame(maxWidth: .infinity).frame(height: 52)
                    .background(Mosimo.primary.opacity(ready && !vm.sending ? 1 : 0.4), in: RoundedRectangle(cornerRadius: 26))
                }
                .disabled(!ready || vm.sending)
                .accessibilityIdentifier("compose.send")
                if !ready {
                    Text(vm.category == nil ? "状況の種類を選んでください" : "投稿する位置を地図で指定してください")
                        .font(.bodySmall).foregroundStyle(Mosimo.warning).frame(maxWidth: .infinity, alignment: .leading)
                }
                HStack(spacing: 4) {
                    Image(systemName: "lock").font(.system(size: 11))
                    Text("投稿はあなたの鍵で署名され、なりすましを防ぎます").font(.bodySmall)
                }
                .foregroundStyle(Mosimo.textTertiary).frame(maxWidth: .infinity)
            }
            .padding(16)
        }
    }

    private func label(_ text: String) -> some View { Text(text).font(.titleMedium).foregroundStyle(Mosimo.textSecondary) }
}

/// Wrapping row of chips (Compose FlowRow).
struct FlowLayout: Layout {
    var spacing: CGFloat = 8
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, row: CGFloat = 0, widest: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width { x = 0; y += row + spacing; row = 0 }
            x += size.width + spacing; row = max(row, size.height); widest = max(widest, x - spacing)
        }
        return CGSize(width: proposal.width ?? widest, height: y + row)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, row: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > bounds.minX && x + size.width > bounds.maxX { x = bounds.minX; y += row + spacing; row = 0 }
            s.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing; row = max(row, size.height)
        }
    }
}
