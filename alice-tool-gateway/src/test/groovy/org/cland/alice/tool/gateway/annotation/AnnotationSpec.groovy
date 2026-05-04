package org.cland.alice.tool.gateway.annotation

import spock.lang.Specification

class AnnotationSpec extends Specification {

    def "@AgentTool should be present on annotated methods"() {
        expect:
        AnnotationTestBeans.AnnotatedBean.declaredMethods.any {
            it.name == "readFile" && it.getAnnotation(AgentTool.class)
        }
        AnnotationTestBeans.AnnotatedBean.declaredMethods.any {
            it.name == "writeFile" && it.getAnnotation(AgentTool.class)
        }
        AnnotationTestBeans.AnnotatedBean.declaredMethods.any {
            it.name == "compute" && it.getAnnotation(AgentTool.class)
        }
    }

    def "@AgentTool should carry correct metadata"() {
        when:
        def readFile = AnnotationTestBeans.AnnotatedBean.declaredMethods
            .find { it.name == "readFile" }.getAnnotation(AgentTool.class)

        then:
        readFile.name() == "file_reader"
        readFile.description() == "Reads content from a local file"
        readFile.risk() == RiskLevel.HIGH

        when:
        def compute = AnnotationTestBeans.AnnotatedBean.declaredMethods
            .find { it.name == "compute" }.getAnnotation(AgentTool.class)

        then:
        compute.name() == "compute"
        compute.description() == "Performs a calculation"
        compute.risk() == RiskLevel.LOW

        when:
        def writeFile = AnnotationTestBeans.AnnotatedBean.declaredMethods
            .find { it.name == "writeFile" }.getAnnotation(AgentTool.class)

        then:
        writeFile.name() == "file_writer"
        writeFile.risk() == RiskLevel.MEDIUM
    }

    def "@ToolParam should be present on annotated parameters"() {
        given:
        def method = AnnotationTestBeans.AnnotatedBean.declaredMethods
            .find { it.name == "readFile" }

        expect:
        method.getParameters()[0].getAnnotation(ToolParam.class) != null
        method.getParameters()[1].getAnnotation(ToolParam.class) != null
    }

    def "@ToolParam should carry correct metadata"() {
        given:
        def params = AnnotationTestBeans.AnnotatedBean.declaredMethods
            .find { it.name == "readFile" }.getParameters()

        when:
        def pathParam = params[0].getAnnotation(ToolParam.class)

        then:
        pathParam.value() == "path"
        pathParam.description() == "File path to read"
        pathParam.required()

        when:
        def encParam = params[1].getAnnotation(ToolParam.class)

        then:
        encParam.value() == "encoding"
        encParam.description() == "File encoding"
        !encParam.required()
    }

    def "@ToolParam on single parameter method"() {
        given:
        def params = AnnotationTestBeans.AnnotatedBean.declaredMethods
            .find { it.name == "compute" }.getParameters()

        when:
        def countParam = params[0].getAnnotation(ToolParam.class)

        then:
        countParam.value() == "count"
        countParam.description() == "Number of iterations"
        countParam.required()
    }

    def "RiskLevel enum should have correct values"() {
        expect:
        RiskLevel.values() as Set == [RiskLevel.LOW, RiskLevel.MEDIUM, RiskLevel.HIGH] as Set
    }

    def "RiskLevel should preserve order"() {
        expect:
        RiskLevel.LOW.ordinal() < RiskLevel.MEDIUM.ordinal()
        RiskLevel.MEDIUM.ordinal() < RiskLevel.HIGH.ordinal()
    }
}
