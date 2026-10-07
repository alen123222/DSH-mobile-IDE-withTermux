package dev.dsh.pocket

import android.app.Activity
import android.os.Bundle
import android.widget.*

/** Deterministic on-device fixture, never shown in the production UI. */
class PhoneFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(EditText(this).apply { contentDescription = "phone-test-input"; setText("initial") })
        layout.addView(EditText(this).apply { inputType = 129; contentDescription = "phone-test-password"; setText("private-marker") })
        layout.addView(Button(this).apply { text = "phone-test-button"; setOnClickListener { text = "phone-test-clicked" } })
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        repeat(70) { content.addView(TextView(this).apply { text = "phone-test-row-$it"; textSize = 24f; setPadding(12, 20, 12, 20) }) }
        layout.addView(ScrollView(this).apply { addView(content) })
        setContentView(layout)
    }
}
