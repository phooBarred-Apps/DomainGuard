package heartguard.heartguard.main;

import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.widget.TooltipCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class RecentTrafficAdapter extends RecyclerView.Adapter<RecentTrafficAdapter.Holder> {
    interface Actions {
        void open(Entry entry);
        void hide(Entry entry);
    }

    static final class App {
        final String name;
        final Drawable icon;
        final String[] packages;

        App(String name, Drawable icon, String[] packages) {
            this.name = name;
            this.icon = icon;
            this.packages = packages.clone();
        }
    }

    static final class Entry {
        final long id, time;
        final int uid;
        final String destination;
        final boolean allowed;
        final App app;

        Entry(long id, long time, int uid, String destination, boolean allowed, App app) {
            this.id = id;
            this.time = time;
            this.uid = uid;
            this.destination = destination;
            this.allowed = allowed;
            this.app = app;
        }
    }

    private List<Entry> entries = Collections.emptyList();
    private final Actions actions;

    RecentTrafficAdapter(Actions actions) {
        this.actions = actions;
        setHasStableIds(true);
    }

    Entry entry(int position) { return entries.get(position); }

    int positionOf(long id) {
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).id == id) return i;
        return -1;
    }

    void submit(List<Entry> updated) {
        List<Entry> previous = entries;
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            public int getOldListSize() { return previous.size(); }
            public int getNewListSize() { return updated.size(); }
            public boolean areItemsTheSame(int oldPosition, int newPosition) {
                return previous.get(oldPosition).id == updated.get(newPosition).id;
            }
            public boolean areContentsTheSame(int oldPosition, int newPosition) {
                Entry old = previous.get(oldPosition), next = updated.get(newPosition);
                return old.time == next.time && old.allowed == next.allowed
                        && old.destination.equals(next.destination) && old.app.name.equals(next.app.name);
            }
        }, false);
        entries = Collections.unmodifiableList(new ArrayList<>(updated));
        diff.dispatchUpdatesTo(this);
    }

    @Override public int getItemCount() { return entries.size(); }
    @Override public long getItemId(int position) { return entry(position).id; }
    @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.recent_traffic_row, parent, false));
    }
    @Override public void onViewRecycled(Holder holder) { holder.bound = holder.pressed = holder.hidePressed = null; }
    @Override public void onBindViewHolder(Holder holder, int position) { holder.bind(entry(position)); }

    final class Holder extends RecyclerView.ViewHolder {
        private Entry bound, pressed, hidePressed;
        private final ImageView icon, outcome;
        private final ImageButton hide;
        private final TextView name, domain, time;

        Holder(View view) {
            super(view);
            icon = view.findViewById(R.id.recent_app_icon);
            outcome = view.findViewById(R.id.recent_outcome);
            hide = view.findViewById(R.id.recent_hide_app);
            name = view.findViewById(R.id.recent_app_name);
            domain = view.findViewById(R.id.recent_domain);
            time = view.findViewById(R.id.recent_time);
            view.setOnTouchListener((target, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) pressed = bound;
                else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) pressed = null;
                return false;
            });
            view.setOnClickListener(target -> {
                Entry selected = pressed == null ? bound : pressed;
                pressed = null;
                if (selected != null) actions.open(selected);
            });
            hide.setOnTouchListener((target, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) hidePressed = bound;
                else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) hidePressed = null;
                return false;
            });
            hide.setOnClickListener(target -> {
                Entry selected = hidePressed == null ? bound : hidePressed;
                hidePressed = null;
                if (selected != null) actions.hide(selected);
            });
        }

        void bind(Entry entry) {
            bound = entry;
            icon.setImageDrawable(entry.app.icon);
            name.setText(entry.app.name);
            domain.setText(entry.destination);
            refreshTime();
            time.setContentDescription(DomainTimestamp.full(entry.time));
            outcome.setImageResource(entry.allowed ? R.drawable.host_allowed : R.drawable.host_blocked);
            outcome.setContentDescription(itemView.getContext().getString(entry.allowed
                    ? R.string.recent_traffic_allowed : R.string.recent_traffic_blocked));
            hide.setContentDescription(itemView.getContext().getString(R.string.recent_traffic_hide, entry.app.name));
            TooltipCompat.setTooltipText(hide, hide.getContentDescription());
        }

        void refreshTime() {
            if (bound != null) time.setText(DomainTimestamp.format(itemView.getContext(), bound.time));
        }
    }
}
