package com.tobrun.datacompat

import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.toTypeParameterResolver
import com.squareup.kotlinpoet.ksp.writeTo
import com.tobrun.datacompat.annotation.DataCompat
import com.tobrun.datacompat.annotation.Default
import java.util.Locale

/**
 * [DataCompatProcessor] is a concrete instance of the [SymbolProcessor] interface.
 * This processor supports multiple round execution, it may return a list of deferred DataCompat annotated symbols.
 * Exceptions or implementation errors will result in a termination of processing immediately and be logged as an error
 * in KSPLogger.
 */
class DataCompatProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        logger.info("DataCompat: process")
        val annotated = resolver.getSymbolsWithAnnotation(DataCompat::class.qualifiedName!!, true)
        if (annotated.count() == 0) {
            logger.info("DataCompat: No DataCompat annotations found for processing")
            return emptyList()
        }

        val unableToProcess = annotated.filterNot { it.validate() }
        annotated.filter { it is KSClassDeclaration && it.validate() }
            .forEach { it.accept(Visitor(), Unit) }
        return unableToProcess.toList()
    }

    private inner class Visitor : KSVisitorVoid() {

        @Suppress("LongMethod", "MaxLineLength", "ComplexMethod")
        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
            if (isInvalidAnnotatedSetup(classDeclaration)) {
                return
            }

            // Cleanup class name by dropping Data part
            // TODO make this part more flexible with providing name inside the annotation
            val className =
                classDeclaration.simpleName.asString().dropLast(CLASS_NAME_DROP_LAST_CHARACTERS)
            val classKdoc = classDeclaration.docString
            val packageName = classDeclaration.packageName.asString()

            val otherAnnotations = classDeclaration.annotations
                .filter { it.annotationType.resolve().toString() != DataCompat::class.simpleName }
            val implementedInterfaces = classDeclaration
                .superTypes
                .filter { (it.resolve().declaration as? KSClassDeclaration)?.classKind == ClassKind.INTERFACE }
            val imports = ArrayList<String>()

            // Map KSP properties with KoltinPoet TypeNames
            val propertyMap = mutableMapOf<KSPropertyDeclaration, TypeName>()
            for (property in classDeclaration.getAllProperties()) {
                val classTypeParams = classDeclaration.typeParameters.toTypeParameterResolver()
                val typeName = property.type.resolve().toTypeName(classTypeParams)
                propertyMap[property] = typeName
            }

            // Build property list for kdoc

            val kdocPropertyList = classKdoc?.let { doc ->
                doc.split("\n")
                    .filter { it.isNotEmpty() && it.contains("@property") }
                    .map { it.substringAfter("$KDOC_PROPERTY_ANNOTATION ") }
            } ?: emptyList()

            // KotlinPoet class builder
            val classBuilder = TypeSpec.classBuilder(className).apply {
                classKdoc?.let {
                    addKdoc(
                        classKdoc.split("\n")
                            .filter { it.isNotEmpty() }.joinToString(
                                separator = "\n",
                                transform = {
                                    if (it.startsWith(" ")) {
                                        it.substring(1)
                                    } else {
                                        it
                                    }
                                }
                            )
                    )
                }

                otherAnnotations.forEach {
                    addAnnotation(
                        it.annotationType.resolve().toClassName()
                    )
                }

                implementedInterfaces.forEach {
                    addSuperinterface(
                        it.resolve().toClassName()
                    )
                }

                // Constructor
                val constructorBuilder = FunSpec.constructorBuilder()
                constructorBuilder.addModifiers(KModifier.PRIVATE)
                for (entry in propertyMap) {
                    constructorBuilder.addParameter(entry.key.toString(), entry.value)
                }
                primaryConstructor(constructorBuilder.build())

                // Property initializers
                for (entry in propertyMap) {
                    addProperty(
                        PropertySpec.builder(entry.key.toString(), entry.value)
                            .initializer(entry.key.toString())
                            .build()
                    )
                }

                // Function toString
                addFunction(
                    FunSpec.builder("toString")
                        .addModifiers(KModifier.OVERRIDE)
                        // using triple quote for long strings
                        .addStatement(
                            propertyMap.keys.joinToString(
                                prefix = "return \"\"\"$className(",
                                transform = { "$it=$$it" },
                                postfix = ")\"\"\".trimIndent()"
                            )
                        )
                        .build()
                )

                // Function equals
                val equalsBuilder = FunSpec.builder("equals")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("other", ANY.copy(nullable = true))
                    .addStatement("if (this === other) return true")
                    .addStatement("if (javaClass != other?.javaClass) return false")
                    .addStatement("other as $className")
                    .addStatement(
                        propertyMap.keys.joinToString(
                            prefix = "return ",
                            separator = " && ",
                            transform = { "$it == other.$it" },
                            postfix = ""
                        )
                    )
                    .returns(Boolean::class)
                addFunction(equalsBuilder.build())

                // Function hashCode
                addFunction(
                    FunSpec.builder("hashCode")
                        .addModifiers(KModifier.OVERRIDE)
                        .addStatement(
                            propertyMap.keys.joinToString(
                                prefix = "return Objects.hash(",
                                separator = ", ",
                                postfix = ")"
                            )
                        )
                        .returns(Int::class)
                        .build()
                )

                // Function toBuilder
                addFunction(
                    FunSpec.builder("toBuilder")
                        .addKdoc(
                            """
                            Convert to Builder allowing to change class properties.
                            """.trimIndent()
                        )
                        .addStatement(
                            propertyMap.keys.joinToString(
                                prefix = "return Builder() .",
                                transform = { str ->
                                    "set${str.toString().replaceFirstChar {
                                        if (it.isLowerCase())
                                            it.titlecase(Locale.getDefault())
                                        else it.toString()
                                    }}($str)"
                                },
                                separator = " .",
                            )
                        )
                        .returns(ClassName("", "Builder"))
                        .build()
                )
            }

            // Builder pattern
            val builderBuilder = TypeSpec.classBuilder("Builder")
            for (property in propertyMap) {
                val propertyName = property.key.toString()
                val defaultAnnotationsParams = property.key.annotations
                    .firstOrNull { it.annotationType.resolve().toString() == Default::class.simpleName }
                    ?.arguments
                val defaultValue = defaultAnnotationsParams?.first()
                defaultAnnotationsParams?.getOrNull(1)?.value?.let {
                    imports.addAll(it as ArrayList<String>)
                }
                val nullableType = property.value.copy(nullable = true)
                builderBuilder.addProperty(
                    PropertySpec.builder(propertyName, nullableType)
                        .initializer(
                            CodeBlock.builder()
                                .add((defaultValue?.value as? String?) ?: "null")
                                .build()
                        )
                        .addAnnotation(
                            AnnotationSpec.builder(JvmSynthetic::class)
                                .useSiteTarget(AnnotationSpec.UseSiteTarget.SET)
                                .build()
                        )
                        .mutable()
                        .build()
                )

                var kDocProperty = kdocPropertyList
                    .filter { it.startsWith("$propertyName ") }
                    .joinToString {
                        it.substringAfter("$propertyName ").lowercase(Locale.getDefault())
                    }

                if (kDocProperty.isEmpty()) {
                    kDocProperty = propertyName
                }

                builderBuilder.addFunction(
                    FunSpec
                        .builder(
                            "set${propertyName.replaceFirstChar {
                                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                            }}"
                        )
                        .addKdoc(
                            """
                            |Set $kDocProperty
                            |
                            |@param $propertyName $kDocProperty
                            |@return Builder
                            """.trimMargin()
                        )
                        .addParameter(propertyName, nullableType)
                        .addStatement("this.$propertyName = $propertyName")
                        .addStatement("return this")
                        .returns(ClassName(packageName, className, "Builder"))
                        .build()
                )
            }

            val buildFunction = FunSpec.builder("build")
            buildFunction.addKdoc(
                """
                |Returns a [$className] reference to the object being constructed by the builder.
                |
                |Throws an [IllegalArgumentException] when a non-null property wasn't initialised.
                |
                |@return $className
                """.trimMargin()
            )
            for (property in propertyMap) {
                if (!property.value.isNullable) {
                    buildFunction.addStatement("if (${property.key}==null) {")
                    val exceptionMessage = "Null ${property.key} found when building $className."
                    buildFunction.addStatement("\tthrow IllegalArgumentException(\"$exceptionMessage\")")
                    buildFunction.addStatement("}")
                }
            }
            buildFunction.addStatement(
                propertyMap.keys.joinToString(
                    prefix = "return $className(",
                    transform = {
                        if (propertyMap[it]!!.isNullable) "$it" else "$it!!"
                    },
                    separator = ", ",
                    postfix = ")"
                )
            )
                .returns(ClassName(packageName, className))

            builderBuilder.addKdoc(
                """
                |Composes and builds a [$className] object.
                |
                |This is a concrete implementation of the builder design pattern.
                |
                |${
                kdocPropertyList.joinToString(
                    prefix = "$KDOC_PROPERTY_ANNOTATION ",
                    separator = "\n$KDOC_PROPERTY_ANNOTATION "
                )
                }
                """.trimMargin()
            )
            builderBuilder.addFunction(buildFunction.build())

            classBuilder.addType(builderBuilder.build())

            // initializer function
            val initializerFunctionBuilder = FunSpec.builder(className)
                .addKdoc(
                    """
                    |Creates a [$className] through a DSL-style builder.
                    |
                    |@param initializer the initialisation block
                    |@return $className
                    """.trimMargin()
                )
                .returns(ClassName(packageName, className))
                .addAnnotation(JvmSynthetic::class)
                .addParameter(
                    ParameterSpec.builder(
                        "initializer",
                        LambdaTypeName.get(
                            ClassName(packageName, className, "Builder"),
                            emptyList(),
                            ClassName("kotlin", "Unit")
                        )
                    ).build()
                )
                .addStatement("return $className.Builder().apply(initializer).build()")

            // File
            val fileBuilder = FileSpec.builder(packageName, className)
                .addImport("java.util", "Objects")
                .addType(classBuilder.build())
                .addFunction(initializerFunctionBuilder.build())

            imports.forEach {
                fileBuilder
                    .addImport(
                        it.split(".").dropLast(1).joinToString("."),
                        it.split(".").last()
                    )
            }

            fileBuilder.build().writeTo(codeGenerator = codeGenerator, aggregating = false)
        }

        @Suppress("ReturnCount")
        private fun isInvalidAnnotatedSetup(classDeclaration: KSClassDeclaration): Boolean {
            val qualifiedName = classDeclaration.qualifiedName?.asString() ?: run {
                logger.error(
                    "@DataClass must target classes with a qualified name",
                    classDeclaration
                )
                return true
            }

            if (!classDeclaration.isDataClass()) {
                logger.error(
                    "@DataClass cannot target a non-data class $qualifiedName",
                    classDeclaration
                )
                return true
            }

            if (!classDeclaration.isPrivate()) {
                logger.error(
                    "@DataClass target must have private visibility",
                    classDeclaration
                )
                return true
            }

            if (classDeclaration.typeParameters.any()) {
                logger.error(
                    "@DataClass target shouldn't have type parameters",
                    classDeclaration
                )
                return true
            }

            if (!classDeclaration.simpleName.asString().endsWith("Data")) {
                logger.error(
                    "@DataClass target must end with Data suffix naming",
                    classDeclaration
                )
                return true
            }
            return false
        }
    }

    private fun KSClassDeclaration.isDataClass() = modifiers.contains(Modifier.DATA)

    private companion object {
        private const val CLASS_NAME_DROP_LAST_CHARACTERS = 4
        private const val KDOC_PROPERTY_ANNOTATION = "@property"
    }
}
