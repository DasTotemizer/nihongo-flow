package app.nihongoflow.core.study

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class InMemoryStudyStoreTest {
    @TestFactory
    fun contract() = StudyStoreContract.all.map { (name, check) ->
        DynamicTest.dynamicTest(name) { check(InMemoryStudyStore()) }
    }
}
