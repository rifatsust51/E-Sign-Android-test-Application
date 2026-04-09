//package com.rvl.myapplication
//
//import android.app.DatePickerDialog
//import android.content.Intent
//import android.graphics.Canvas
//import android.graphics.Color
//import android.graphics.Paint
//import android.graphics.pdf.PdfDocument
//import android.os.Bundle
//import android.widget.Toast
//import androidx.appcompat.app.AppCompatActivity
//import com.google.android.material.button.MaterialButton
//import com.google.android.material.textfield.TextInputEditText
//import com.google.android.material.textfield.TextInputLayout
//import java.io.File
//import java.io.FileOutputStream
//import java.util.Calendar
//
//class MainActivity : AppCompatActivity() {
//
//    // Input Fields
//    private lateinit var etFullName: TextInputEditText
//    private lateinit var etEmail: TextInputEditText
//    private lateinit var etPhone: TextInputEditText
//    private lateinit var etNid: TextInputEditText
//    private lateinit var etDob: TextInputEditText
//    private lateinit var etEmergencyContact: TextInputEditText
//
//    // Layouts (Used to show the red error messages)
//    private lateinit var tilFullName: TextInputLayout
//    private lateinit var btnGenerate: MaterialButton
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_main)
//
//        // Connect everything to your XML IDs
//        etFullName = findViewById(R.id.etFullName)
//        etEmail = findViewById(R.id.etEmail)
//        etPhone = findViewById(R.id.etPhone)
//        etNid = findViewById(R.id.etNid)
//        etDob = findViewById(R.id.etDob)
//        etEmergencyContact = findViewById(R.id.etEmergencyContact)
//
//        tilFullName = findViewById(R.id.tilFullName)
//        btnGenerate = findViewById(R.id.btnGenerate)
//
//        setupDatePicker()
//
//        btnGenerate.setOnClickListener {
//            val name = etFullName.text.toString().trim()
//
//            // Simple Validation: Check if Name is empty
//            if (name.isEmpty()) {
//                tilFullName.error = "Name is required" // Shows red Material error!
//                Toast.makeText(this, "Please enter your name!", Toast.LENGTH_SHORT).show()
//            } else {
//                tilFullName.error = null // Clear the error
//                createPdfAndGoToNextScreen()
//            }
//        }
//    }
//
//    private fun setupDatePicker() {
//        // Stop the keyboard, show the calendar
//        etDob.setOnClickListener {
//            val cal = Calendar.getInstance()
//            DatePickerDialog(this, { _, y, m, d ->
//                etDob.setText("$d/${m + 1}/$y")
//            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
//        }
//    }
//
//    private fun createPdfAndGoToNextScreen() {
//        val pdfDocument = PdfDocument()
//        val pageInfo = PdfDocument.PageInfo.Builder(400, 600, 1).create()
//        val page = pdfDocument.startPage(pageInfo)
//        val canvas: Canvas = page.canvas
//
//        // Make paper White
//        val bgPaint = Paint().apply { color = Color.WHITE }
//        canvas.drawRect(0f, 0f, 400f, 600f, bgPaint)
//
//        // Pen for writing
//        val titlePaint = Paint().apply {
//            textSize = 20f
//            color = Color.BLACK
//            isFakeBoldText = true
//        }
//        val textPaint = Paint().apply {
//            textSize = 14f
//            color = Color.DKGRAY
//        }
//
//        // Draw data onto PDF
//        canvas.drawText("EMPLOYEE PROFILE", 100f, 50f, titlePaint)
//        canvas.drawText("Full Name: ${etFullName.text}", 20f, 100f, textPaint)
//        canvas.drawText("Email: ${etEmail.text}", 20f, 130f, textPaint)
//        canvas.drawText("Phone: +880 ${etPhone.text}", 20f, 160f, textPaint)
//        canvas.drawText("NID: ${etNid.text}", 20f, 190f, textPaint)
//        canvas.drawText("Date of Birth: ${etDob.text}", 20f, 220f, textPaint)
//        canvas.drawText("Emergency Contact: +880 ${etEmergencyContact.text}", 20f, 250f, textPaint)
//
//        pdfDocument.finishPage(page)
//
//        try {
//            // Save it securely
//            val file = File(cacheDir, "TempProfile.pdf")
//            pdfDocument.writeTo(FileOutputStream(file))
//            pdfDocument.close()
//
//            // Jump to Preview Screen
//            val intent = Intent(this, PdfPreviewActivity::class.java)
//            intent.putExtra("FILE_PATH", file.absolutePath)
//            startActivity(intent)
//
//        } catch (e: Exception) {
//            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
//            pdfDocument.close()
//        }
//    }
//}


package com.rvl.myapplication

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Patterns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    // ══════════════════════════════════════════════════════════════════════
    //  INPUT FIELDS
    // ══════════════════════════════════════════════════════════════════════
    private lateinit var etFullName: TextInputEditText
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPhone: TextInputEditText
    private lateinit var etNid: TextInputEditText
    private lateinit var etDob: TextInputEditText
    private lateinit var etEmergencyContact: TextInputEditText

    // ══════════════════════════════════════════════════════════════════════
    //  TEXT INPUT LAYOUTS (For error display)
    // ══════════════════════════════════════════════════════════════════════
    private lateinit var tilFullName: TextInputLayout
    private lateinit var tilEmail: TextInputLayout
    private lateinit var tilPhone: TextInputLayout
    private lateinit var tilNid: TextInputLayout
    private lateinit var tilDob: TextInputLayout
    private lateinit var tilEmergencyContact: TextInputLayout

    // ══════════════════════════════════════════════════════════════════════
    //  BUTTON
    // ══════════════════════════════════════════════════════════════════════
    private lateinit var btnGenerate: MaterialButton

    // ══════════════════════════════════════════════════════════════════════
    //  DATE PICKER STATE
    // ══════════════════════════════════════════════════════════════════════
    private var selectedDateMillis: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Views
        initViews()

        // Setup Date Picker
        setupMaterialDatePicker()

        // Setup Real-time Validation
        setupTextWatchers()

        // Generate Button Click
        btnGenerate.setOnClickListener {
            if (validateAllFields()) {
                createPdfAndGoToNextScreen()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  INITIALIZE VIEWS
    // ══════════════════════════════════════════════════════════════════════
    private fun initViews() {
        // Input Fields
        etFullName = findViewById(R.id.etFullName)
        etEmail = findViewById(R.id.etEmail)
        etPhone = findViewById(R.id.etPhone)
        etNid = findViewById(R.id.etNid)
        etDob = findViewById(R.id.etDob)
        etEmergencyContact = findViewById(R.id.etEmergencyContact)

        // Text Input Layouts
        tilFullName = findViewById(R.id.tilFullName)
        tilEmail = findViewById(R.id.tilEmail)
        tilPhone = findViewById(R.id.tilPhone)
        tilNid = findViewById(R.id.tilNid)
        tilDob = findViewById(R.id.tilDob)
        tilEmergencyContact = findViewById(R.id.tilEmergencyContact)

        // Button
        btnGenerate = findViewById(R.id.btnGenerate)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  MATERIAL DATE PICKER (Industry Standard)
    // ══════════════════════════════════════════════════════════════════════
    private fun setupMaterialDatePicker() {
        // Make the field non-editable but clickable
        etDob.isFocusable = false
        etDob.isFocusableInTouchMode = false
        etDob.isClickable = true

        // Calculate date constraints
        val today = MaterialDatePicker.todayInUtcMilliseconds()

        // Min date: 100 years ago
        val calendarMin = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendarMin.timeInMillis = today
        calendarMin.add(Calendar.YEAR, -100)

        // Max date: 18 years ago (user must be at least 18)
        val calendarMax = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendarMax.timeInMillis = today
        calendarMax.add(Calendar.YEAR, -18)

        // Default selection: 25 years ago
        val calendarDefault = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendarDefault.timeInMillis = today
        calendarDefault.add(Calendar.YEAR, -25)

        // Build constraints
        val constraintsBuilder = CalendarConstraints.Builder()
            .setStart(calendarMin.timeInMillis)
            .setEnd(calendarMax.timeInMillis)
            .setOpenAt(calendarDefault.timeInMillis)
            .setValidator(DateValidatorPointBackward.before(calendarMax.timeInMillis))

        // Build the date picker
        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Select Date of Birth")
            .setSelection(selectedDateMillis ?: calendarDefault.timeInMillis)
            .setCalendarConstraints(constraintsBuilder.build())
            .setTheme(com.google.android.material.R.style.ThemeOverlay_MaterialComponents_MaterialCalendar)
            .build()

        // Click listener for the field
        val showDatePicker: () -> Unit = {
            if (!datePicker.isAdded) {
                datePicker.show(supportFragmentManager, "DATE_PICKER")
            }
        }

        etDob.setOnClickListener { showDatePicker() }

        // Also handle the end icon click
        tilDob.setEndIconOnClickListener { showDatePicker() }

        // Handle date selection
        datePicker.addOnPositiveButtonClickListener { selection ->
            selectedDateMillis = selection

            // Format the date
            val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            dateFormat.timeZone = TimeZone.getTimeZone("UTC")
            val formattedDate = dateFormat.format(Date(selection))

            etDob.setText(formattedDate)
            tilDob.error = null // Clear error
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  REAL-TIME ERROR CLEARING
    // ══════════════════════════════════════════════════════════════════════
    private fun setupTextWatchers() {
        etFullName.addTextChangedListener(createTextWatcher(tilFullName))
        etEmail.addTextChangedListener(createTextWatcher(tilEmail))
        etPhone.addTextChangedListener(createTextWatcher(tilPhone))
        etNid.addTextChangedListener(createTextWatcher(tilNid))
        etEmergencyContact.addTextChangedListener(createTextWatcher(tilEmergencyContact))
    }

    private fun createTextWatcher(layout: TextInputLayout): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Clear error when user starts typing
                layout.error = null
            }

            override fun afterTextChanged(s: Editable?) {}
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  VALIDATION LOGIC
    // ══════════════════════════════════════════════════════════════════════
    private fun validateAllFields(): Boolean {
        var isValid = true
        var firstErrorField: TextInputEditText? = null

        // ─────────────────────────────────────────────────────────
        // 1. FULL NAME VALIDATION
        // ─────────────────────────────────────────────────────────
        val fullName = etFullName.text.toString().trim()
        when {
            fullName.isEmpty() -> {
                tilFullName.error = "Full name is required"
                if (firstErrorField == null) firstErrorField = etFullName
                isValid = false
            }

            fullName.length < 3 -> {
                tilFullName.error = "Name must be at least 3 characters"
                if (firstErrorField == null) firstErrorField = etFullName
                isValid = false
            }

            fullName.length > 50 -> {
                tilFullName.error = "Name must be less than 50 characters"
                if (firstErrorField == null) firstErrorField = etFullName
                isValid = false
            }

            !fullName.matches(Regex("^[a-zA-Z\\s.'-]+$")) -> {
                tilFullName.error = "Only letters, spaces, dots, apostrophes, hyphens allowed"
                if (firstErrorField == null) firstErrorField = etFullName
                isValid = false
            }

            fullName.trim().split("\\s+".toRegex()).size < 2 -> {
                tilFullName.error = "Please enter your full name (first and last name)"
                if (firstErrorField == null) firstErrorField = etFullName
                isValid = false
            }

            else -> {
                tilFullName.error = null
            }
        }

        // ─────────────────────────────────────────────────────────
        // 2. EMAIL VALIDATION
        // ─────────────────────────────────────────────────────────
        val email = etEmail.text.toString().trim()
        when {
            email.isEmpty() -> {
                tilEmail.error = "Email address is required"
                if (firstErrorField == null) firstErrorField = etEmail
                isValid = false
            }

            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                tilEmail.error = "Please enter a valid email address"
                if (firstErrorField == null) firstErrorField = etEmail
                isValid = false
            }

            email.length > 100 -> {
                tilEmail.error = "Email must be less than 100 characters"
                if (firstErrorField == null) firstErrorField = etEmail
                isValid = false
            }

            else -> {
                tilEmail.error = null
            }
        }

        // ─────────────────────────────────────────────────────────
        // 3. PHONE NUMBER VALIDATION (Bangladesh: 10-11 digits starting with 1)
        // ─────────────────────────────────────────────────────────
        val phone = etPhone.text.toString().trim()
        when {
            phone.isEmpty() -> {
                tilPhone.error = "Phone number is required"
                if (firstErrorField == null) firstErrorField = etPhone
                isValid = false
            }

            !phone.matches(Regex("^[0-9]+$")) -> {
                tilPhone.error = "Phone number can only contain digits"
                if (firstErrorField == null) firstErrorField = etPhone
                isValid = false
            }

            phone.length < 10 -> {
                tilPhone.error = "Phone number must be at least 10 digits"
                if (firstErrorField == null) firstErrorField = etPhone
                isValid = false
            }

            phone.length > 11 -> {
                tilPhone.error = "Phone number must not exceed 11 digits"
                if (firstErrorField == null) firstErrorField = etPhone
                isValid = false
            }

            !phone.startsWith("1") -> {
                tilPhone.error = "Phone number must start with 1 (after +880)"
                if (firstErrorField == null) firstErrorField = etPhone
                isValid = false
            }

            else -> {
                tilPhone.error = null
            }
        }

        // ─────────────────────────────────────────────────────────
        // 4. NID VALIDATION (Bangladesh: 10, 13, or 17 digits)
        // ─────────────────────────────────────────────────────────
        val nid = etNid.text.toString().trim()
        when {
            nid.isEmpty() -> {
                tilNid.error = "National ID is required"
                if (firstErrorField == null) firstErrorField = etNid
                isValid = false
            }

            !nid.matches(Regex("^[0-9]+$")) -> {
                tilNid.error = "NID can only contain digits"
                if (firstErrorField == null) firstErrorField = etNid
                isValid = false
            }

            nid.length !in listOf(10, 13, 17) -> {
                tilNid.error = "NID must be 10, 13, or 17 digits"
                if (firstErrorField == null) firstErrorField = etNid
                isValid = false
            }

            else -> {
                tilNid.error = null
            }
        }

        // ─────────────────────────────────────────────────────────
        // 5. DATE OF BIRTH VALIDATION
        // ─────────────────────────────────────────────────────────
        val dob = etDob.text.toString().trim()
        when {
            dob.isEmpty() -> {
                tilDob.error = "Date of birth is required"
                if (firstErrorField == null) firstErrorField = etDob
                isValid = false
            }

            !isValidAge(dob) -> {
                tilDob.error = "You must be at least 18 years old"
                if (firstErrorField == null) firstErrorField = etDob
                isValid = false
            }

            else -> {
                tilDob.error = null
            }
        }

        // ─────────────────────────────────────────────────────────
        // 6. EMERGENCY CONTACT VALIDATION
        // ─────────────────────────────────────────────────────────
        val emergencyContact = etEmergencyContact.text.toString().trim()
        when {
            emergencyContact.isEmpty() -> {
                tilEmergencyContact.error = "Emergency contact is required"
                if (firstErrorField == null) firstErrorField = etEmergencyContact
                isValid = false
            }

            !emergencyContact.matches(Regex("^[0-9]+$")) -> {
                tilEmergencyContact.error = "Emergency contact can only contain digits"
                if (firstErrorField == null) firstErrorField = etEmergencyContact
                isValid = false
            }

            emergencyContact.length < 10 -> {
                tilEmergencyContact.error = "Emergency contact must be at least 10 digits"
                if (firstErrorField == null) firstErrorField = etEmergencyContact
                isValid = false
            }

            emergencyContact.length > 11 -> {
                tilEmergencyContact.error = "Emergency contact must not exceed 11 digits"
                if (firstErrorField == null) firstErrorField = etEmergencyContact
                isValid = false
            }

            !emergencyContact.startsWith("1") -> {
                tilEmergencyContact.error = "Number must start with 1 (after +880)"
                if (firstErrorField == null) firstErrorField = etEmergencyContact
                isValid = false
            }

            emergencyContact == phone -> {
                tilEmergencyContact.error = "Emergency contact cannot be same as your phone"
                if (firstErrorField == null) firstErrorField = etEmergencyContact
                isValid = false
            }

            else -> {
                tilEmergencyContact.error = null
            }
        }

        // Show error toast and focus first error field
        if (!isValid) {
            Toast.makeText(this, "Please fix the errors above", Toast.LENGTH_SHORT).show()
            firstErrorField?.requestFocus()
        }

        return isValid
    }

    // ══════════════════════════════════════════════════════════════════════
    //  AGE VALIDATION HELPER
    // ══════════════════════════════════════════════════════════════════════
    private fun isValidAge(dateString: String): Boolean {
        return try {
            val parts = dateString.split("/")
            if (parts.size != 3) return false

            val day = parts[0].toIntOrNull() ?: return false
            val month = (parts[1].toIntOrNull() ?: return false) - 1 // 0-indexed
            val year = parts[2].toIntOrNull() ?: return false

            val birthDate = Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, day)
            }

            val today = Calendar.getInstance()

            var age = today.get(Calendar.YEAR) - birthDate.get(Calendar.YEAR)

            // Adjust if birthday hasn't occurred this year
            val todayDayOfYear = today.get(Calendar.DAY_OF_YEAR)
            val birthDayOfYear = birthDate.get(Calendar.DAY_OF_YEAR)

            if (todayDayOfYear < birthDayOfYear) {
                age--
            }

            age >= 18
        } catch (e: Exception) {
            false
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PDF GENERATION
    // ══════════════════════════════════════════════════════════════════════
    private fun createPdfAndGoToNextScreen() {
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(400, 600, 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas: Canvas = page.canvas

        // ─────────────────────────────────────────────────────────
        // PAINTS
        // ─────────────────────────────────────────────────────────
        val bgPaint = Paint().apply { color = Color.WHITE }

        val titlePaint = Paint().apply {
            textSize = 22f
            color = Color.parseColor("#1C1917")
            isFakeBoldText = true
            isAntiAlias = true
        }

        val labelPaint = Paint().apply {
            textSize = 10f
            color = Color.parseColor("#78716C")
            isFakeBoldText = true
            isAntiAlias = true
            letterSpacing = 0.1f
        }

        val valuePaint = Paint().apply {
            textSize = 14f
            color = Color.parseColor("#1C1917")
            isAntiAlias = true
        }

        val dividerPaint = Paint().apply {
            color = Color.parseColor("#E7E5E4")
            strokeWidth = 1f
        }

        val footerPaint = Paint().apply {
            textSize = 9f
            color = Color.parseColor("#A8A29E")
            isAntiAlias = true
        }

        val accentPaint = Paint().apply {
            color = Color.parseColor("#F59E0B")
        }

        // ─────────────────────────────────────────────────────────
        // GET DATA
        // ─────────────────────────────────────────────────────────
        val fullName = etFullName.text.toString().trim()
        val email = etEmail.text.toString().trim()
        val phone = etPhone.text.toString().trim()
        val nid = etNid.text.toString().trim()
        val dob = etDob.text.toString().trim()
        val emergencyContact = etEmergencyContact.text.toString().trim()

        // ─────────────────────────────────────────────────────────
        // DRAW BACKGROUND
        // ─────────────────────────────────────────────────────────
        canvas.drawRect(0f, 0f, 400f, 600f, bgPaint)

        // Accent bar at top
        canvas.drawRect(0f, 0f, 400f, 6f, accentPaint)

        // ─────────────────────────────────────────────────────────
        // DRAW HEADER
        // ─────────────────────────────────────────────────────────
        canvas.drawText("Employee Profile", 24f, 50f, titlePaint)
        canvas.drawLine(24f, 65f, 376f, 65f, dividerPaint)

        // ─────────────────────────────────────────────────────────
        // DRAW FIELDS
        // ─────────────────────────────────────────────────────────
        var yPosition = 100f
        val lineHeight = 50f
        val labelOffset = 14f

        // Helper function to draw a field
        fun drawField(label: String, value: String) {
            canvas.drawText(label.uppercase(), 24f, yPosition, labelPaint)
            canvas.drawText(value, 24f, yPosition + labelOffset, valuePaint)
            yPosition += lineHeight
        }

        drawField("Full Name", fullName)
        drawField("Email Address", email)
        drawField("Phone Number", "+880 $phone")
        drawField("National ID (NID)", formatNid(nid))
        drawField("Date of Birth", dob)
        drawField("Emergency Contact", "+880 $emergencyContact")

        // ─────────────────────────────────────────────────────────
        // DRAW FOOTER
        // ─────────────────────────────────────────────────────────
        canvas.drawLine(24f, 380f, 376f, 380f, dividerPaint)

        canvas.drawText("Generated on: ${getCurrentDateTime()}", 24f, 400f, footerPaint)
        canvas.drawText(
            "This document requires a digital signature for authentication.",
            24f,
            415f,
            footerPaint
        )
        canvas.drawText("Powered by Relief Validation Ltd.", 24f, 430f, footerPaint)

        pdfDocument.finishPage(page)

        // ─────────────────────────────────────────────────────────
        // SAVE PDF
        // ─────────────────────────────────────────────────────────
        try {
            val file = File(cacheDir, "TempProfile.pdf")
            pdfDocument.writeTo(FileOutputStream(file))
            pdfDocument.close()

            // Navigate to Preview Screen
            val intent = Intent(this, PdfPreviewActivity::class.java)
            intent.putExtra("FILE_PATH", file.absolutePath)
            startActivity(intent)

        } catch (e: Exception) {
            Toast.makeText(this, "Error creating PDF: ${e.message}", Toast.LENGTH_SHORT).show()
            pdfDocument.close()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  HELPER: Format NID with spaces for readability
    // ══════════════════════════════════════════════════════════════════════
    private fun formatNid(nid: String): String {
        return when (nid.length) {
            10 -> "${nid.substring(0, 3)} ${nid.substring(3, 6)} ${nid.substring(6)}"
            13 -> "${nid.substring(0, 4)} ${nid.substring(4, 8)} ${nid.substring(8)}"
            17 -> "${nid.substring(0, 4)} ${nid.substring(4, 8)} ${
                nid.substring(
                    8,
                    12
                )
            } ${nid.substring(12)}"

            else -> nid
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  HELPER: Get Current Date & Time
    // ══════════════════════════════════════════════════════════════════════
    private fun getCurrentDateTime(): String {
        val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        return dateFormat.format(Date())
    }
}