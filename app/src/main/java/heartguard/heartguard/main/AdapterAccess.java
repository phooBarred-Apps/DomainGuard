package heartguard.heartguard.main;

/*
    This file is part of NetGuard.

    NetGuard is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    NetGuard is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2015-2019 by Marcel Bokhorst (M66B)
*/

import android.content.Context;
import android.text.SpannableString;
import android.text.style.UnderlineSpan;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class AdapterAccess extends RecyclerView.Adapter<AdapterAccess.Holder> {
    interface Click { void open(AccessEntry entry); }
    private final Click click;
    private List<AccessEntry> entries = new ArrayList<>();
    private final AccessRowStyle rowStyle;

    AdapterAccess(Context context, Click click) {
        this.click = click;
        setHasStableIds(true);
        rowStyle = new AccessRowStyle(context);
    }

    void submit(List<AccessEntry> next) {
        List<AccessEntry> previous = entries;
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            public int getOldListSize() { return previous.size(); }
            public int getNewListSize() { return next.size(); }
            public boolean areItemsTheSame(int oldPosition, int newPosition) {
                return previous.get(oldPosition).id == next.get(newPosition).id;
            }
            public boolean areContentsTheSame(int oldPosition, int newPosition) {
                return previous.get(oldPosition).sameContents(next.get(newPosition));
            }
        });
        entries = new ArrayList<>(next);
        diff.dispatchUpdatesTo(this);
    }

    @Override public int getItemCount() { return entries.size(); }
    @Override public long getItemId(int position) { return entries.get(position).id; }
    @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.access, parent, false));
    }
    final class Holder extends RecyclerView.ViewHolder {
        AccessEntry bound, pressed;
        Holder(View view) {
            super(view);
            view.setOnTouchListener((target, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) pressed = bound;
                else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) pressed = null;
                return false;
            });
            view.setOnClickListener(target -> {
                AccessEntry selected = pressed == null ? bound : pressed;
                pressed = null;
                if (selected != null) click.open(selected);
            });
        }
    }
    @Override public void onViewRecycled(Holder holder) { holder.bound = holder.pressed = null; }
    @Override public void onBindViewHolder(Holder holder, int position) {
        AccessEntry entry = entries.get(position);
        holder.bound = entry;
        View view = holder.itemView;
        Context context = view.getContext();
        int version = entry.version, protocol = entry.protocol, dport = entry.port;
        String daddr = entry.address;
        long time = entry.time, sent = entry.sent, received = entry.received;
        int count = entry.aliases;
        int connections = entry.connections;
        // Get views
        TextView tvTime = view.findViewById(R.id.tvTime);
        ImageView ivBlock = view.findViewById(R.id.ivBlock);
        final TextView tvDest = view.findViewById(R.id.tvDest);
        LinearLayout llTraffic = view.findViewById(R.id.llTraffic);
        TextView tvConnections = view.findViewById(R.id.tvConnections);
        TextView tvTraffic = view.findViewById(R.id.tvTraffic);

        // Set values
        tvTime.setText(DomainTimestamp.format(context, time));
        tvTime.setContentDescription(DomainTimestamp.full(time));
        rowStyle.bind(entry, ivBlock, tvDest);
        rowStyle.bindHistory(entry, tvTime);

        String dest = Util.getProtocolName(protocol, version, true) +
                " " + daddr + (dport > 0 ? "/" + dport : "") + (count > 1 ? " ?" + count : "");
        SpannableString span = new SpannableString(dest);
        span.setSpan(new UnderlineSpan(), 0, dest.length(), 0);
        tvDest.setText(daddr);
        tvDest.setContentDescription(dest);

        llTraffic.setVisibility(connections > 0 || sent > 0 || received > 0 ? View.VISIBLE : View.GONE);
        if (connections > 0)
            tvConnections.setText(context.getString(R.string.msg_count, connections));

        if (sent > 1024 * 1204 * 1024L || received > 1024 * 1024 * 1024L)
            tvTraffic.setText(context.getString(R.string.msg_gb,
                    (sent > 0 ? sent / (1024 * 1024 * 1024f) : 0),
                    (received > 0 ? received / (1024 * 1024 * 1024f) : 0)));
        else if (sent > 1204 * 1024L || received > 1024 * 1024L)
            tvTraffic.setText(context.getString(R.string.msg_mb,
                    (sent > 0 ? sent / (1024 * 1024f) : 0),
                    (received > 0 ? received / (1024 * 1024f) : 0)));
        else
            tvTraffic.setText(context.getString(R.string.msg_kb,
                    (sent > 0 ? sent / 1024f : 0),
                    (received > 0 ? received / 1024f : 0)));
    }
}
