package au.edu.unimelb.floraguide.domain

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class Issue14CoreTest(private val name: String, private val runCase: () -> Unit) {
    @Test fun regression() { runCase() }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = Issue14CoreContract.cases().map { (name, test) -> arrayOf(name, test) }
    }
}
