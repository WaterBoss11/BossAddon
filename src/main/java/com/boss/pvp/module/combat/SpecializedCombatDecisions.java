package com.boss.pvp.module.combat;

/** Headless decisions shared only by the three specialized-combat modules and their checks. */
final class SpecializedCombatDecisions {
    // The shared arbiter only permits non-healing emergency preemption at or below priority 20.
    static final int PREEMPTIBLE_SLOT_PRIORITY = 20;

    private SpecializedCombatDecisions() {}

    enum ChargeWindow { WINDUP, ACTIVE, SPENT }

    static ChargeWindow chargeWindow(int usedTicks, int delayTicks, int lastDamageTick) {
        if (usedTicks < delayTicks) return ChargeWindow.WINDUP;
        return usedTicks <= lastDamageTick ? ChargeWindow.ACTIVE : ChargeWindow.SPENT;
    }

    static int lungeSteps(double distance, double maximumStep, int remainingDamageTicks) {
        if (!Double.isFinite(distance) || !Double.isFinite(maximumStep) || distance <= 0 || distance > 1.5
            || maximumStep < 0.1 || maximumStep > 0.35) return 0;
        int steps = (int) Math.ceil(distance / maximumStep);
        return steps <= 8 && remainingDamageTicks >= steps * 4 + 2 ? steps : 0;
    }

    static boolean sameHorizontalPosition(double actualX, double actualZ, double expectedX, double expectedZ,
                                          double tolerance) {
        double dx = actualX - expectedX;
        double dz = actualZ - expectedZ;
        return Double.isFinite(tolerance) && tolerance >= 0 && Double.isFinite(dx) && Double.isFinite(dz)
            && dx * dx + dz * dz <= tolerance * tolerance;
    }

    static boolean protectedCell(int x, int y, int z, double minX, double minY, double minZ,
                                 double maxX, double maxY, double maxZ) {
        int lowX = (int) Math.floor(minX + 1.0e-5);
        int highX = (int) Math.floor(maxX - 1.0e-5);
        int lowZ = (int) Math.floor(minZ + 1.0e-5);
        int highZ = (int) Math.floor(maxZ - 1.0e-5);
        int feet = (int) Math.floor(minY + 1.0e-5);
        int head = (int) Math.floor(maxY - 1.0e-5);
        if (x < lowX - 1 || x > highX + 1 || z < lowZ - 1 || z > highZ + 1) return false;
        boolean footprint = x >= lowX && x <= highX && z >= lowZ && z <= highZ;
        return y >= feet && y <= head || footprint && y == feet - 1;
    }

    enum PushStep { IDLE, PISTON, PISTON_ACK, POWER, POWER_ACK, EXTENSION, COMPLETE }

    static final class PushSequence {
        private PushStep step = PushStep.IDLE;
        private int age;
        private int totalAge;
        private int retries;

        PushStep step() { return step; }
        int age() { return age; }
        int retries() { return retries; }

        void start() {
            step = PushStep.PISTON;
            age = totalAge = retries = 0;
            powerSeen = extensionSeen = false;
        }

        void reset() {
            step = PushStep.IDLE;
            age = totalAge = retries = 0;
            powerSeen = extensionSeen = false;
        }

        boolean tick(int totalBudget) {
            age++;
            return ++totalAge <= totalBudget;
        }

        void sent() {
            if (step == PushStep.PISTON) transition(PushStep.PISTON_ACK);
            else if (step == PushStep.POWER) transition(PushStep.POWER_ACK);
            else throw new IllegalStateException("Placement outside a placement step");
        }

        void confirmPiston(boolean facingCorrect, boolean extended) {
            if (!facingCorrect) return;
            if (step == PushStep.PISTON_ACK) transition(PushStep.POWER);
            if (extended && (step == PushStep.POWER_ACK || step == PushStep.EXTENSION)) {
                extensionSeen = true;
                if (powerSeen) transition(PushStep.COMPLETE);
            }
        }

        private boolean powerSeen;
        private boolean extensionSeen;

        void confirmPower() {
            if (step != PushStep.POWER_ACK && step != PushStep.EXTENSION) return;
            powerSeen = true;
            transition(extensionSeen ? PushStep.COMPLETE : PushStep.EXTENSION);
        }

        boolean retryRejected(int maximumRetries) {
            if (retries >= maximumRetries) return false;
            if (step == PushStep.PISTON_ACK) transition(PushStep.PISTON);
            else if (step == PushStep.POWER_ACK) {
                powerSeen = extensionSeen = false;
                transition(PushStep.POWER);
            } else return false;
            retries++;
            return true;
        }

        private void transition(PushStep next) {
            step = next;
            age = 0;
        }
    }
}
