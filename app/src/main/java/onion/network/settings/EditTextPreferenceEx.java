

package onion.network.settings;

import android.content.Context;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.util.AttributeSet;

public class EditTextPreferenceEx extends EditTextPreference {

    public EditTextPreferenceEx(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public EditTextPreferenceEx(Context context) {
        super(context);
        init();
    }

    private void init() {
        setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference p, Object a) {
                p.setSummary(getText());
                return true;
            }
        });
    }

    @Override
    public CharSequence getSummary() {
        return getText();
    }

}
