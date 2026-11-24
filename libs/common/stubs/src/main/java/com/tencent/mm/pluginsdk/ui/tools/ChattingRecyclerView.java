package com.tencent.mm.pluginsdk.ui.tools;

import android.content.Context;
import android.util.AttributeSet;

/**
 * 微信聊天消息列表的实际 RecyclerView 包装类桩。
 * <p>
 * 8.0.65–8.0.76 中 {@code ScrollControlRecyclerView extends ChattingRecyclerView},
 * 一次 {@code is ChattingRecyclerView} 检查即可覆盖两种运行时类型。
 * <p>
 * 故意声明为 {@code ViewGroup} 而不是 RecyclerView: 桩工程不引 AndroidX。
 * 需要 RecyclerView 的公开方法时, 在本类里按签名补一个 throwing 方法直接用 ——
 * 不要写 {@code as? RecyclerView}: 模块打包了 androidx.recyclerview, 与宿主那份
 * 未必是同一个类, cast 失败会静默返回 null, 表现为功能"开了没反应"。
 */
public class ChattingRecyclerView extends android.view.ViewGroup {

    public ChattingRecyclerView(Context context) {
        super(context);
    }

    public ChattingRecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ChattingRecyclerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /**
     * 消息总数 (含头/尾视图), 各版本保留 (反编译 ChattingRecyclerView / m3|v3 类的 getCount)。
     * 返回 int, 签名不涉及宿主类型, 可以安全地通过桩直接调用。
     */
    public int getCount() {
        throw new RuntimeException("Stub!");
    }

    /**
     * 头/尾视图数量。外层包装 adapter 的 getItemCount() = header + footer + 内层消息数,
     * 计算"最后一条消息的内层数据位置"需要把它们扣掉 (m3|v3 / r3 类)。
     */
    public int getHeaderViewsCount() {
        throw new RuntimeException("Stub!");
    }

    public int getFooterViewsCount() {
        throw new RuntimeException("Stub!");
    }

    public boolean isAttachedToWindow() {
        throw new RuntimeException("Stub!");
    }

    // 以下是 RecyclerView 的公开滚动量 API, 相距底部 = range - offset - extent。
    // 直接在桩里声明, 调用方就不必 `as? RecyclerView`（见类注释）。

    public int computeVerticalScrollRange() {
        throw new RuntimeException("Stub!");
    }

    public int computeVerticalScrollOffset() {
        throw new RuntimeException("Stub!");
    }

    public int computeVerticalScrollExtent() {
        throw new RuntimeException("Stub!");
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
    }
}
