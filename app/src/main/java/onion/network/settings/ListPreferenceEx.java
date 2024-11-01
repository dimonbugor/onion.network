

package onion.network.settings;

import android.content.Context;
import android.preference.ListPreference;
import android.preference.Preference;
import android.util.AttributeSet;

public class ListPreferenceEx extends ListPreference {

    public ListPreferenceEx(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ListPreferenceEx(Context context) {
        super(context);
        init();
    }

    private void init() {
        setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference p, Object a) {
                p.setSummary(getEntry());
                return true;
            }
        });
    }

    @Override
    public CharSequence getSummary() {
        return super.getEntry();
    }

}
