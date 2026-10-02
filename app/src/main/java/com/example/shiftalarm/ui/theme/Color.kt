package com.example.shiftalarm.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// Shift-specific colors
val ShiftMorning = Color(0xFFFFA726)    // Orange - morning shift
val ShiftAfternoon = Color(0xFF42A5F5)  // Blue - afternoon shift
val ShiftNight = Color(0xFF5C6BC0)      // Indigo - night shift
val ShiftCustom = Color(0xFF66BB6A)     // Green - custom shift

val CalendarToday = Color(0xFFFFF3E0)
val CalendarSelected = Color(0xFFE3F2FD)
val CalendarShiftBorder = Color(0xFFBDBDBD)

// Predefined palette for template color picker
val TemplateColors = listOf(
    0xFF42A5F5L, // Blue
    0xFFEF5350L, // Red
    0xFF66BB6AL, // Green
    0xFFFFA726L, // Orange
    0xFFAB47BCL, // Purple
    0xFF26C6DAL, // Teal
    0xFFEC407AL, // Pink
    0xFF7E57C2L, // Deep Purple
    0xFFFF7043L, // Deep Orange
    0xFF8D6E63L, // Brown
    0xFF78909CL, // Blue Grey
    0xFF5C6BC0L, // Indigo
    0xFF26A69AL, // Teal (alternative)
    0xFFD4E157L, // Lime
    0xFFFFCA28L, // Amber
    0xFFE53935L, // Strong Red
    0xFF00ACC1L, // Cyan
    0xFF6D4C41L, // Dark Brown
    0xFF9CCC65L, // Light Green
    0xFFFF80ABL, // Light Pink
    0xFFB39DDBL, // Light Purple
    0xFFFDD835L, // Yellow
    0xFF4DB6ACL, // Light Teal
    0xFFF48FB1L, // Soft Pink
    0xFF81D4FAL, // Light Blue
    0xFFA1887FL, // Greyish Brown
    0xFFCE93D8L, // Soft Purple
    0xFF90A4AEL, // Grey Blue
    0xFFF4511EL, // Vermilion
    0xFF00897BL, // Dark Teal
    0xFFFFB74DL, // Light Orange
    0xFFE57373L, // Soft Red
) // 32 colors

fun Long.toComposeColor(): Color = Color(this)