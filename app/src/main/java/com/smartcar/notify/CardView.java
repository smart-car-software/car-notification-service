/*
 * Copyright © 2026 smart-car-software contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.smartcar.notify;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One card, drawn the way barrier-opener drew its gate question on the cluster: the cluster's own
 * navigation-hint panel (dark translucent slate, thin lighter rim), the selected answer in its
 * light-blue accent frame, the rest as plain text. Sizes are px on purpose — the card is laid out
 * against that one panel on that one display, not against a density bucket; the head unit gets
 * the same card multiplied by {@code scale}.
 *
 * <p>Built in code rather than inflated: a question has one to four answers and a list any number
 * of rows, and a layout file per count would only be this loop written out.
 */
final class CardView {

    interface Tap {
        /** Head unit only: an answer or row was touched; -1 for the card itself (information). */
        void onTap(int index);
    }

    final View root;
    private final Card card;
    private final TextView[] answers;
    private final TextView[] rows;
    private TextView up;
    private TextView down;

    /** @param tap null on the cluster, which has no touch */
    CardView(Context context, Card card, float scale, Tap tap) {
        this.card = card;
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.cluster_card_background);
        box.setPadding(px(18, scale), px(12, scale), px(18, scale), px(14, scale));

        TextView title = text(context, card.title, 26, scale, R.color.cluster_text);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(card.type == Card.Type.INFO ? 2 : 1);
        box.addView(title, matchWrap());
        if (!card.text.isEmpty()) {
            TextView sub = text(context, card.text, 22, scale, R.color.cluster_text_dim);
            sub.setGravity(Gravity.CENTER);
            sub.setMaxLines(card.type == Card.Type.INFO ? 3 : 2);
            LinearLayout.LayoutParams lp = matchWrap();
            lp.topMargin = px(4, scale);
            box.addView(sub, lp);
        }

        TextView[] a = new TextView[0];
        TextView[] r = new TextView[0];
        if (card.type == Card.Type.QUESTION) {
            LinearLayout line = new LinearLayout(context);
            line.setOrientation(LinearLayout.HORIZONTAL);
            a = new TextView[card.options.length];
            for (int i = 0; i < a.length; i++) {
                TextView v = text(context, card.options[i], 24, scale, R.color.cluster_text);
                v.setGravity(Gravity.CENTER);
                v.setPadding(0, px(8, scale), 0, px(8, scale));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                lp.leftMargin = px(4, scale);
                lp.rightMargin = px(4, scale);
                line.addView(v, lp);
                a[i] = v;
                if (tap != null) {
                    final int index = i;
                    v.setOnClickListener(x -> tap.onTap(index));
                }
            }
            LinearLayout.LayoutParams lp = matchWrap();
            lp.topMargin = px(10, scale);
            box.addView(line, lp);
        } else if (card.type == Card.Type.LIST) {
            up = arrow(context, "▲", scale);
            LinearLayout.LayoutParams ulp = matchWrap();
            ulp.topMargin = px(6, scale);
            box.addView(up, ulp);
            r = new TextView[Math.min(CardLayout.VISIBLE_ROWS, card.options.length)];
            for (int i = 0; i < r.length; i++) {
                TextView v = text(context, "", 24, scale, R.color.cluster_text);
                v.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                v.setPadding(px(12, scale), px(6, scale), px(12, scale), px(6, scale));
                LinearLayout.LayoutParams lp = matchWrap();
                lp.topMargin = px(2, scale);
                box.addView(v, lp);
                r[i] = v;
            }
            down = arrow(context, "▼", scale);
            box.addView(down, matchWrap());
        } else if (tap != null) {
            box.setOnClickListener(x -> tap.onTap(-1));
        }
        answers = a;
        rows = r;
        if (tap != null && rows.length > 0) {
            for (TextView v : rows) {
                // The row's index in the list is set at each render; read it back from the tag.
                v.setOnClickListener(x -> {
                    Object index = x.getTag();
                    if (index instanceof Integer) {
                        tap.onTap((Integer) index);
                    }
                });
            }
        }
        root = box;
    }

    /**
     * @param selected     the answer or row the wheel rests on
     * @param firstVisible a list's first row in view, from {@link CardLayout#firstVisible}
     */
    void render(int selected, int firstVisible) {
        for (int i = 0; i < answers.length; i++) {
            paint(answers[i], i == selected);
        }
        if (rows.length == 0) {
            return;
        }
        for (int i = 0; i < rows.length; i++) {
            int index = firstVisible + i;
            TextView v = rows[i];
            v.setText(card.options[index]);
            v.setTag(index);
            paint(v, index == selected);
        }
        up.setVisibility(firstVisible > 0 ? View.VISIBLE : View.INVISIBLE);
        down.setVisibility(firstVisible + rows.length < card.options.length
                ? View.VISIBLE : View.INVISIBLE);
    }

    private static void paint(TextView v, boolean selected) {
        if (selected) {
            v.setBackgroundResource(R.drawable.cluster_answer_selected);
        } else {
            v.setBackground(null);
        }
    }

    private static TextView arrow(Context context, String glyph, float scale) {
        TextView v = text(context, glyph, 16, scale, R.color.cluster_text_dim);
        v.setGravity(Gravity.CENTER);
        return v;
    }

    private static TextView text(Context context, String s, int sizePx, float scale, int color) {
        TextView v = new TextView(context);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx * scale);
        v.setTextColor(context.getColor(color));
        v.setSingleLine(false);
        v.setMaxLines(1);
        v.setEllipsize(TextUtils.TruncateAt.END);
        return v;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static int px(int base, float scale) {
        return Math.round(base * scale);
    }
}
