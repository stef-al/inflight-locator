package inflight.engine

import kotlin.system.exitProcess

/** Entry point for the engine tests: `selftest` or `mc <options>` (see engine/README.md). */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "selftest" -> {
            val t0 = System.currentTimeMillis()
            val checks = SelfTest.math() + SelfTestSim.run() + SelfTestSensors.run()
            for (c in checks) println("${if (c.passed) "PASS" else "FAIL"}  ${c.name}: ${c.detail}")
            val failed = checks.count { !it.passed }
            println("${checks.size - failed} of ${checks.size} checks passed in ${System.currentTimeMillis() - t0} ms")
            if (failed > 0) exitProcess(1)
        }
        "mc" -> MonteCarlo.main(args.drop(1).toTypedArray())
        "pipeline" -> Pipeline.main(args.drop(1).toTypedArray())
        "plan" -> print(FlightPlan.w64747().encode())
        else -> {
            println("usage: selftest | pipeline [--runs N] [--no-baro] [--handle] [--mode known|gc] | mc [--runs N] [--seed S] [--particles P] [--scenario w64747|long_ns|long_ew] [--only a,b] [--jobs J] [--airports file] [--out file] [--check]")
            exitProcess(2)
        }
    }
}
