package com.atakmap.android.takconvo.plugin.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.text.Layout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;

import com.google.android.material.textfield.MaterialAutoCompleteTextView;

/**
 * The account pane's address field, with the server's domain ({@code @example.org}) right
 * after the username in the hint's colour. The domain gives way: it runs out of the field on
 * the right while the username stays in view. Material's suffix text keeps its whole width
 * instead, and a username longer than what is left scrolls out to the left.
 */
public final class DomainSuffixField extends MaterialAutoCompleteTextView {

    private CharSequence suffix;

    public DomainSuffixField(final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    /** Text drawn after the username, or null for none. */
    public void setSuffix(final CharSequence suffix) {
        if (!TextUtils.equals(this.suffix, suffix)) {
            this.suffix = suffix;
            invalidate();
        }
    }

    @Override
    protected void onFocusChanged(final boolean focused, final int direction,
            final Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
        // shown in an empty field only once the label has moved up
        invalidate();
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        super.onDraw(canvas);
        final Layout layout = getLayout();
        // empty and not focused: the field's label is where the suffix would be
        if (suffix == null || layout == null || (length() == 0 && !hasFocus())) {
            return;
        }
        final TextPaint paint = getPaint();
        final int textColor = paint.getColor();
        paint.setColor(getCurrentHintTextColor());
        canvas.save();
        // the text area as it shows: the canvas scrolls with the text
        canvas.clipRect(getScrollX() + getCompoundPaddingLeft(), getScrollY(),
                getScrollX() + getWidth() - getCompoundPaddingRight(),
                getScrollY() + getHeight());
        canvas.drawText(suffix, 0, suffix.length(),
                getCompoundPaddingLeft() + layout.getLineRight(0), getBaseline(), paint);
        canvas.restore();
        paint.setColor(textColor);
    }
}
