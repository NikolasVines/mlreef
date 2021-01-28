package com.mlreef.parsing

import com.mlreef.antlr.Python3BaseVisitor
import com.mlreef.antlr.Python3Lexer
import com.mlreef.antlr.Python3Parser
import com.mlreef.rest.DataAlgorithm
import com.mlreef.rest.DataOperation
import com.mlreef.rest.DataProcessor
import com.mlreef.rest.DataProcessorType
import com.mlreef.rest.DataType
import com.mlreef.rest.DataVisualization
import com.mlreef.rest.EPFAnnotation
import com.mlreef.rest.MetricSchema
import com.mlreef.rest.MetricType
import com.mlreef.rest.ParameterType
import com.mlreef.rest.ProcessorParameter
import com.mlreef.rest.VisibilityScope
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import java.io.InputStream
import java.util.UUID
import java.util.logging.Logger


fun parsePython3(inStream: InputStream, errorMessage: MutableList<String>? = null): MLParseResult {
    val parser: Python3Parser = CharStreams.fromStream(inStream)
        .let { Python3Lexer(it) }
        .let { CommonTokenStream(it) }
        .let { Python3Parser(it) }

    parser.setParsingErrorProcessor { row, col, msg ->
        errorMessage?.apply {
            add("Error in line $row, column $col: $msg")
        }
    }

    return MLVisitor()
        .apply { visit(parser.file_input()) }
        .validatedResult
}

class MLParseResult {
    var mlAnnotations: List<EPFAnnotation> = mutableListOf()
    var countFunctions: Int = 0
    var countMLDecoratedFunctions: Int = 0
    var countMLFunctionParameters: Int = 0
    var countMLDataProcessor: Int = 0
    var countMLMetricSchema: Int = 0

    /** Validates python variable names to be alphanumeric + underscore. */
    fun validate() {
        val nameValidator = Regex("[a-zA-Z0-9_-]*")

        val annotatedParameterNames = mlAnnotations.filterIsInstance<ProcessorParameter>().map { it.name }

        annotatedParameterNames.forEach {
            if (!nameValidator.matches(it)) {
                throw BadParameterNameException(it)
            }
        }
    }
}

internal class MLVisitor(private val result: MLParseResult = MLParseResult()) : Python3BaseVisitor<Any>() {
    private val logger: Logger = Logger.getLogger(javaClass.simpleName)
    val validatedResult: MLParseResult
        get() {
            result.validate()
            return result
        }

    override fun visitFuncdef(ctx: Python3Parser.FuncdefContext?) {
        if (ctx == null) return
        result.countFunctions += 1
    }


    // Annotations on Python are called Decorators
    // ==> one Decorated context is equal to an annotation
    override fun visitDecorated(context: Python3Parser.DecoratedContext?) {
        if (context == null) return
        logger.info("parsing decorated function: ${context.funcdef()?.NAME()} with ${context.decorators()?.text}")
        result.countFunctions += 1
        nullSafeVisitDecorated(context)
    }

    private fun nullSafeVisitDecorated(context: Python3Parser.DecoratedContext) {
        var index = 0
        var dataProcessorId: UUID? = null

        context.decorators().children
            .filterIsInstance<Python3Parser.DecoratorContext>()
            .mapNotNull {
                println("Annotation: ${it.text}")
                try {
                    //e.g. @parameter(parameters...)
                    //@parameters => Dotted_namedContext
                    //parameters.. => arglist
                    //children[0] = "@"
                    //children[1] = "parameter"
                    //children[2] = "("
                    //children[3] = parameters...
                    //children[4] = "4"
                    val mlAnnotation = create(
                        nameContext = it.children[1] as Python3Parser.Dotted_nameContext,
                        arglistContext = it.children[3] as Python3Parser.ArglistContext,
                        dataProcessorId = dataProcessorId,
                        index = index++,
                    )
                    result.mlAnnotations += mlAnnotation
                    when (mlAnnotation) {
                        is DataProcessor -> {
                            result.countMLDataProcessor += 1
                            dataProcessorId = mlAnnotation.id
                        }
                        is ProcessorParameter -> result.countMLFunctionParameters += 1
                        is MetricSchema -> result.countMLMetricSchema += 1
                        else -> logger.warning("Could not handle:$mlAnnotation")
                    }
                    return@mapNotNull mlAnnotation
                } catch (error: Exception) {
                    logger.warning(error.message)
                    return@mapNotNull null // will be filtered out and thus ignored
                }
            }
            .also {
                if (it.isNotEmpty()) result.countMLDecoratedFunctions += 1
            }
    }
}


abstract class PythonParserException(message: String) : IllegalStateException(message)
class MultipleDataProcessorsFoundException : PythonParserException("Multiple DataProcessors were found, but just 1 is supported")
class BadParameterNameException(name: String) : PythonParserException("Bad parameter naming: $name")


private fun create(
    nameContext: Python3Parser.Dotted_nameContext,
    arglistContext: Python3Parser.ArglistContext,
    dataProcessorId: UUID?,
    index: Int
): EPFAnnotation {
    val name: String = nameContext.text
    val arguments = arglistContext.argument()
    // transform context to map
    val argMap: Map<String, String> = arguments
        .filter { argumentContext -> argumentContext.text.isNotBlank() && argumentContext.test().size > 1 }
        .map { argumentContext ->
            val tupleKey = argumentContext.test(0).text
            val tupleValue = argumentContext.test(1).text.replace("\"", "")
            tupleKey to tupleValue.clean()
        }.toMap()
    // transform context orderedList
    val argList: List<String> = arguments
        .filter { it.text.isNotBlank() && it.test().size > 0 }
        .map { it.test(0).text.clean() }
    return when (name) {
        // hardcoded name for MLReef annotations
        "parameter" -> createParameter(argMap, argList, dataProcessorId!!, index)
        "data_processor" -> createDataOperation(argMap)
        "metric" -> createMetric(argMap, argList)
        else -> throw IllegalArgumentException("Not supported Annotation: $name")
    }
}


fun createDataOperation(values: Map<String, String>): EPFAnnotation {
    val slug: String = values["slug"]
        ?: values["name"]?.toSlug()
        ?: throw NullPointerException()
    val name: String = values["name"]
        ?: values["slug"]
        ?: throw NullPointerException()
    val command: String = values["command"]
        ?: "$slug.py"
    val description: String = values["description"] ?: ""

    val processorType = DataProcessorType.valueOf(enumOrFail(values, "type"))
    val inputDataType = DataType.valueOf(enumOrFail(values, "input_type"))
    val outputDataType = DataType.valueOf(enumOrFail(values, "output_type"))
    val visibilityScope = VisibilityScope.valueOf(enumOrFail(values, "visibility"))

    return when (processorType) {
        DataProcessorType.VISUALIZATION -> DataVisualization(
            id = UUID.randomUUID(),
            slug = slug,
            name = name,
            author = null,
            description = description,
            inputDataType = inputDataType,
            visibilityScope = visibilityScope,
        )
        DataProcessorType.OPERATION -> DataOperation(
            id = UUID.randomUUID(),
            name = name,
            slug = slug,
            author = null,
            description = description,
            inputDataType = inputDataType,
            outputDataType = outputDataType,
            visibilityScope = visibilityScope,
        )
        DataProcessorType.ALGORITHM -> DataAlgorithm(
            id = UUID.randomUUID(),
            name = name,
            slug = slug,
            author = null,
            description = description,
            inputDataType = inputDataType,
            outputDataType = outputDataType,
            visibilityScope = visibilityScope,
        )
    }
}

private fun createParameter(namedArguments: Map<String, String>, sequentialArguments: List<String>, dataProcessorId: UUID, index: Int): EPFAnnotation {
    var name: String? = null
    var parameterTypeString: String? = null
    var requiredString: String? = null
    var defaultValue: String? = null
    var description: String? = null

    val countOrderedValues = sequentialArguments.size - namedArguments.size
    if (countOrderedValues > 0) {
        // begin with parsing the ordered arguments
        name = takeUntil(sequentialArguments, 0, countOrderedValues)
        parameterTypeString = takeUntil(sequentialArguments, 1, countOrderedValues)
        requiredString = takeUntil(sequentialArguments, 2, countOrderedValues)
        defaultValue = takeUntil(sequentialArguments, 3, countOrderedValues)
        description = takeUntil(sequentialArguments, 4, countOrderedValues)
    }
    name = name ?: getOrFail(namedArguments, "name")
    parameterTypeString = parameterTypeString ?: getOrFail(namedArguments, "type")
    requiredString = requiredString ?: getOrFail(namedArguments, "required")
    defaultValue = defaultValue ?: getOrFail(namedArguments, "defaultValue")
    description = description ?: getOrEmpty(namedArguments, "description")

    return ProcessorParameter(
        id = UUID.randomUUID(),
        processorVersionId = dataProcessorId,
        name = name,
        type = getParameterType(parameterTypeString),
        required = getBoolean(requiredString),
        defaultValue = defaultValue,
        order = index,
        description = description
    )
}

// @metric(name='recall', ground_truth=test_truth, prediction=test_pred)
private fun createMetric(namedArguments: Map<String, String>, sequentialArguments: List<String>): EPFAnnotation {
    var typeString: String? = null
    var groundTruth: String? = null
    var prediction: String? = null

    val countOrderedValues = sequentialArguments.size - namedArguments.size
    if (countOrderedValues > 0) {
        // begin with parsing the ordered arguments
        typeString = takeUntil(sequentialArguments = sequentialArguments, index = 0, upperBount = countOrderedValues)
        groundTruth = takeUntil(sequentialArguments, 1, countOrderedValues)
        prediction = takeUntil(sequentialArguments, 2, countOrderedValues)
    }
    typeString = typeString ?: getOrFail(namedArguments, "name")
    groundTruth = groundTruth ?: getOrFail(namedArguments, "ground_truth")
    prediction = prediction ?: getOrFail(namedArguments, "prediction")

    return MetricSchema(
        metricType = getMetricType(typeString),
        groundTruth = groundTruth,
        prediction = prediction
    )
}

private fun getParameterType(parameterTypeString: String): ParameterType =
    when (parameterTypeString.toUpperCase()) {
        ("STRING") -> ParameterType.STRING
        ("STR") -> ParameterType.STRING
        ("INTEGER") -> ParameterType.INTEGER
        ("INT") -> ParameterType.INTEGER
        ("FLOAT") -> ParameterType.FLOAT
        ("BOOLEAN") -> ParameterType.BOOLEAN
        ("BOOL") -> ParameterType.BOOLEAN
        ("COMPLEX") -> ParameterType.COMPLEX
        ("DICTIONARY") -> ParameterType.DICTIONARY
        ("LIST") -> ParameterType.LIST
        ("TUPLE") -> ParameterType.TUPLE
        else -> ParameterType.UNDEFINED
    }

private fun getMetricType(parameterTypeString: String): MetricType {
    return when (parameterTypeString.toUpperCase()) {
        ("RECALL") -> MetricType.RECALL
        ("PRECISION") -> MetricType.PRECISION
        ("F1_SCORE") -> MetricType.F1_SCORE
        ("F1") -> MetricType.F1_SCORE
        else -> MetricType.UNDEFINED
    }
}


private fun String.clean(): String = this
    .replace("\"", "")
    .replace("'", "")

private fun String?.toSlug(): String? = this?.toLowerCase()?.replace(Regex("[\\t\\s -_]"), "-")

private fun takeUntil(sequentialArguments: List<String>, index: Int, upperBount: Int): String? =
    if (index >= upperBount) {
        null
    } else {
        sequentialArguments.getOrNull(index)
    }

private fun getBoolean(arg: String): Boolean =
    when (arg.toUpperCase()) {
        ("TRUE") -> true
        ("FALSE") -> false
        else -> false
    }

private fun enumOrFail(values: Map<String, String>, key: String): String = getOrFail(values, key).toUpperCase()

private fun getOrFail(values: Map<String, String>, key: String): String {
    return if (values.containsKey(key)) {
        values[key]!!
    } else {
        error("No $key provided")
    }
}

private fun getOrEmpty(values: Map<String, String>, key: String): String {
    return if (values.containsKey(key)) {
        values[key]!!
    } else {
        ""
    }
}
