

package onion.network.settings;

import android.content.Context;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

public class EditTextPreferenceUrl extends EditTextPreferenceEx {
/**/
    public EditTextPreferenceUrl(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public EditTextPreferenceUrl(Context context) {
        super(context);
    }

    @Override
    public EditText getEditText() {
        EditText ed = super.getEditText();
        ed.setImeOptions(ed.getImeOptions() | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        ed.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        return ed;
    }
}
