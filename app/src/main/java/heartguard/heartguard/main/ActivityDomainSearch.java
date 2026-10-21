package heartguard.heartguard.main;

import android.os.Bundle;
import android.view.MenuItem;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;

/** Standalone entry point sharing the main screen's domain results and copy behavior. */
public class ActivityDomainSearch extends AppCompatActivity {
    private SearchView search;
    private DomainSearchResults results;

    @Override protected void onCreate(Bundle state) {
        Util.setTheme(this);
        super.onCreate(state);
        setContentView(R.layout.domain_search);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        search = findViewById(R.id.domain_search);
        results = new DomainSearchResults(this, findViewById(android.R.id.content));
        search.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            public boolean onQueryTextSubmit(String query) {
                search.clearFocus();
                results.setQuery(query);
                return true;
            }
            public boolean onQueryTextChange(String query) {
                results.setQuery(query);
                return true;
            }
        });
        if (state != null) search.setQuery(state.getString("query", ""), false);
        search.clearFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        results.start();
    }

    @Override protected void onPause() {
        results.stop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        results.close();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("query", search.getQuery().toString());
        super.onSaveInstanceState(state);
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
