package com.family.share.ui;

import android.content.res.ColorStateList;
import android.graphics.drawable.BitmapDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.family.share.R;
import com.family.share.data.Member;
import com.family.share.util.AvatarLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * 家人列表适配器：本机排最前，其余按服务器返回顺序。
 * 列表项 = 圆形头像（名字首字）+ 名称 + 更新时间/状态 + 在线状态点。
 */
public class MemberAdapter extends RecyclerView.Adapter<MemberAdapter.VH> {

    public interface Listener {
        void onClick(Member m);

        void onLongClick(Member m);
    }

    private final List<Member> data = new ArrayList<>();
    private final LayoutInflater inflater;
    private final Listener listener;
    private String myDeviceId = "";

    public MemberAdapter(LayoutInflater inflater, Listener listener) {
        this.inflater = inflater;
        this.listener = listener;
    }

    /**
     * 整体刷新成员列表（本机排最前）。用 DiffUtil 只对发生变化的行做局部刷新，
     * 避免整表 notifyDataSetChanged 造成“绿点闪烁”；适合周期刷新（在线/头像/群主状态及时更新）。
     */
    public void update(List<Member> list, String myDeviceId) {
        this.myDeviceId = myDeviceId;
        // 重新排序：本机排最前
        final List<Member> newList = new ArrayList<>();
        for (Member m : list) {
            if (m.deviceId.equals(myDeviceId)) {
                newList.add(m);
            }
        }
        for (Member m : list) {
            if (!m.deviceId.equals(myDeviceId)) {
                newList.add(m);
            }
        }
        final List<Member> oldList = new ArrayList<>(data);
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return oldList.size();
            }

            @Override
            public int getNewListSize() {
                return newList.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return oldList.get(oldItemPosition).deviceId.equals(newList.get(newItemPosition).deviceId);
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return sameContent(oldList.get(oldItemPosition), newList.get(newItemPosition));
            }
        }, false);
        data.clear();
        data.addAll(newList);
        result.dispatchUpdatesTo(this);
    }

    /** 判断两个 Member 的“显示相关”字段是否一致（不一致才重绑该行） */
    private static boolean sameContent(Member a, Member b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.deviceId.equals(b.deviceId)
                && nullEq(a.name, b.name)
                && a.online == b.online
                && a.offlineMode == b.offlineMode
                && a.isOwner == b.isOwner
                && a.hasLocation == b.hasLocation
                && a.ts == b.ts
                && a.accuracy == b.accuracy
                && a.battery == b.battery
                && nullEq(a.network, b.network)
                && nullEq(a.address, b.address)
                && nullEq(a.avatar, b.avatar);
    }

    private static boolean nullEq(String x, String y) {
        if (x == null && y == null) {
            return true;
        }
        if (x == null || y == null) {
            return false;
        }
        return x.equals(y);
    }

    /**
     * 仅刷新某个成员所在的行（位置/状态变化时调用），避免整表 notifyDataSetChanged 造成“绿点闪烁”。
     * 只在列表已存在该成员时生效（新成员/已移除成员由调用方走全量刷新 update()）。
     * 直接替换 data 中的对象并 notifyItemChanged，行序（自己始终在首位）保持不变。
     */
    public void onMemberUpdated(Member m, String myDeviceId) {
        this.myDeviceId = myDeviceId;
        if (m == null || m.deviceId == null || m.deviceId.isEmpty()) {
            return;
        }
        for (int i = 0; i < data.size(); i++) {
            if (m.deviceId.equals(data.get(i).deviceId)) {
                data.set(i, m);
                notifyItemChanged(i);
                return;
            }
        }
        // 不在列表中：留给调用方走全量刷新，这里不做任何操作
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(inflater.inflate(R.layout.item_member, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Member m = data.get(position);
        boolean isSelf = m.deviceId.equals(myDeviceId);

        h.tvName.setText(isSelf ? (m.name + "（我）") : m.name);
        h.tvTime.setText(timeText(m));
        h.dot.setBackgroundResource(m.online ? R.drawable.dot_online : R.drawable.dot_offline);
        // 群主标识
        if (h.tvOwnerBadge != null) {
            h.tvOwnerBadge.setVisibility(m.isOwner ? View.VISIBLE : View.GONE);
        }

        // 头像：已上传显示图片（圆形裁剪），否则名字首字 + 与地图标点一致的配色
        String initial = (m.name != null && !m.name.isEmpty()) ? m.name.substring(0, 1) : "?";
        int color = isSelf ? MemberColors.selfColor() : MemberColors.colorFor(m.deviceId);
        if (m.avatar != null && !m.avatar.isEmpty()) {
            h.avatar.setTag(m.deviceId);
            AvatarLoader.load(m.avatar, bmp -> {
                if (!m.deviceId.equals(h.avatar.getTag())) {
                    return;
                }
                if (bmp != null) {
                    h.avatar.setText("");
                    h.avatar.setBackgroundTintList(null);
                    h.avatar.setBackground(new BitmapDrawable(h.avatar.getResources(),
                            AvatarLoader.circleCrop(bmp)));
                } else {
                    // 加载失败：回退为首字彩圈
                    h.avatar.setText(initial);
                    h.avatar.setBackgroundResource(R.drawable.bg_avatar);
                    h.avatar.setBackgroundTintList(ColorStateList.valueOf(color));
                }
            });
        } else {
            h.avatar.setTag(null);
            h.avatar.setText(initial);
            h.avatar.setBackgroundResource(R.drawable.bg_avatar);
            h.avatar.setBackgroundTintList(ColorStateList.valueOf(color));
        }

        h.itemView.setOnClickListener(v -> listener.onClick(m));
        h.itemView.setOnLongClickListener(v -> {
            listener.onLongClick(m);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    private String timeText(Member m) {
        if (m.ts <= 0) {
            return inflater.getContext().getString(R.string.member_no_location);
        }
        String t = relativeTime(m.ts);
        if (m.accuracy > 0) {
            t = t + " · ±" + Math.round(m.accuracy) + "m";
        }
        return m.online ? t : t + " · " + inflater.getContext().getString(R.string.status_member_offline);
    }

    private String relativeTime(long ts) {
        long diff = System.currentTimeMillis() - ts;
        if (diff < 60_000) {
            return inflater.getContext().getString(R.string.time_just_now);
        }
        if (diff < 3_600_000) {
            return inflater.getContext().getString(R.string.time_minutes_ago, diff / 60_000);
        }
        if (diff < 86_400_000) {
            return inflater.getContext().getString(R.string.time_hours_ago, diff / 3_600_000);
        }
        return inflater.getContext().getString(R.string.time_days_ago, diff / 86_400_000);
    }

    static class VH extends RecyclerView.ViewHolder {
        final View dot;
        final TextView avatar;
        final TextView tvName;
        final TextView tvOwnerBadge;
        final TextView tvTime;

        VH(@NonNull View itemView) {
            super(itemView);
            dot = itemView.findViewById(R.id.dotOnline);
            avatar = itemView.findViewById(R.id.avatar);
            tvName = itemView.findViewById(R.id.tvName);
            tvOwnerBadge = itemView.findViewById(R.id.tvOwnerBadge);
            tvTime = itemView.findViewById(R.id.tvTime);
        }
    }
}
