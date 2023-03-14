package com.tobrun.data.compat.example

import com.tobrun.datacompat.annotation.DataCompat
import com.tobrun.datacompat.annotation.Default

interface SampleInterface
annotation class SampleAnnotation

/**
 * Represents a person.
 * @property name The full name.
 * @property nickname The nickname.
 * @property age The age.
 */
@DataCompat
@SampleAnnotation
private data class PersonData(
    @Default("\"John\" + Date(1580897313933L).toString()", imports = ["java.util.Date"])
    val name: String,
    val nickname: String?,
    @Default("42")
    val age: Int
) : SampleInterface


@DataCompat
@SampleAnnotation
private data class PersonData2(
    @Default("\"Josdfhn\" + Dateasdfa(1580897313933L).toString()", imports = ["java.util.Date"])
    val name: String,
    val nickname: String?,
    @Default("4fsdf2")
    val age: Int
) : SampleInterface