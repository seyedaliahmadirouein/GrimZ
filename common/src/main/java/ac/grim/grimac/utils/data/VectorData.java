package ac.grim.grimac.utils.data;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.StuckSpeed;
import lombok.Getter;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import java.util.Objects;

public class VectorData {
    public final VectorType vectorType;
    public VectorData lastVector;
    public VectorData preUncertainty;
    public Vector3dm vector;
    public @MonotonicNonNull Vector3dm input;
    public IndexedVector3d stuckSpeedMultiplier = StuckSpeed.NONE;

    @Getter
    private boolean isKnockback, firstBreadKb, isExplosion, firstBreadExplosion, isTrident, isZeroPointZeroThree, isSwimHop, isFlipSneaking, isFlipItem, isJump, isAttackSlow = false;

    // For handling replacing the type of vector it is while keeping data
    public VectorData(Vector3dm vector, VectorData lastVector, VectorType vectorType) {
        this.vector = vector;
        this.lastVector = lastVector;
        this.vectorType = vectorType;

        if (lastVector != null) {
            isKnockback = lastVector.isKnockback;
            firstBreadKb = lastVector.firstBreadKb;
            isExplosion = lastVector.isExplosion;
            firstBreadExplosion = lastVector.firstBreadExplosion;
            isTrident = lastVector.isTrident;
            isZeroPointZeroThree = lastVector.isZeroPointZeroThree;
            isSwimHop = lastVector.isSwimHop;
            isFlipSneaking = lastVector.isFlipSneaking;
            isFlipItem = lastVector.isFlipItem;
            isJump = lastVector.isJump;
            preUncertainty = lastVector.preUncertainty;
            isAttackSlow = lastVector.isAttackSlow;
            input = lastVector.input;
            stuckSpeedMultiplier = lastVector.stuckSpeedMultiplier;
        }

        addVectorType(vectorType);
    }

    public VectorData(Vector3dm vector, VectorType vectorType) {
        this.vector = vector;
        this.vectorType = vectorType;
        addVectorType(vectorType);
    }

    public VectorData returnNewModified(VectorType type) {
        return new VectorData(vector, this, type);
    }

    public VectorData returnNewModified(Vector3dm newVec, VectorType type) {
        return new VectorData(newVec, this, type);
    }

    public boolean isSetbackKb(GrimPlayer player) {
        if (!isKnockback) {
            return false;
        }
        VelocityData bread = (firstBreadKb ? player.firstBreadKB : player.likelyKB);
        return bread != null && bread.isSetback;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VectorData that = (VectorData) o;
        return isKnockback == that.isKnockback && firstBreadKb == that.firstBreadKb && isExplosion == that.isExplosion && firstBreadExplosion == that.firstBreadExplosion && isTrident == that.isTrident && isZeroPointZeroThree == that.isZeroPointZeroThree && isSwimHop == that.isSwimHop && isFlipSneaking == that.isFlipSneaking && isFlipItem == that.isFlipItem && isJump == that.isJump && isAttackSlow == that.isAttackSlow && vectorType == that.vectorType && Objects.equals(lastVector, that.lastVector) && Objects.equals(preUncertainty, that.preUncertainty) && Objects.equals(vector, that.vector) && Objects.equals(stuckSpeedMultiplier, that.stuckSpeedMultiplier);
    }

    @Override
    public int hashCode() {
        // Identical to Objects.hash(...) but without allocating a 16-slot Object[]
        // and without boxing the booleans on every call (this is a per-HashSet-op hot path).
        // Objects.hash delegates to Arrays.hashCode(Object[]), which starts at 1.
        int result = 1;
        result = 31 * result + (vectorType != null ? vectorType.hashCode() : 0);
        result = 31 * result + (lastVector != null ? lastVector.hashCode() : 0);
        result = 31 * result + (preUncertainty != null ? preUncertainty.hashCode() : 0);
        result = 31 * result + (vector != null ? vector.hashCode() : 0);
        result = 31 * result + (stuckSpeedMultiplier != null ? stuckSpeedMultiplier.hashCode() : 0);
        result = 31 * result + (isKnockback ? 1231 : 1237);
        result = 31 * result + (firstBreadKb ? 1231 : 1237);
        result = 31 * result + (isExplosion ? 1231 : 1237);
        result = 31 * result + (firstBreadExplosion ? 1231 : 1237);
        result = 31 * result + (isTrident ? 1231 : 1237);
        result = 31 * result + (isZeroPointZeroThree ? 1231 : 1237);
        result = 31 * result + (isSwimHop ? 1231 : 1237);
        result = 31 * result + (isFlipSneaking ? 1231 : 1237);
        result = 31 * result + (isFlipItem ? 1231 : 1237);
        result = 31 * result + (isJump ? 1231 : 1237);
        result = 31 * result + (isAttackSlow ? 1231 : 1237);
        return result;
    }

    public void addVectorType(VectorType type) {
        switch (type) {
            case Knockback -> isKnockback = true;
            case FirstBreadKnockback -> firstBreadKb = true;
            case Explosion -> isExplosion = true;
            case FirstBreadExplosion -> firstBreadExplosion = true;
            case Trident -> isTrident = true;
            case ZeroPointZeroThree -> isZeroPointZeroThree = true;
            case Swimhop -> isSwimHop = true;
            case Flip_Sneaking -> isFlipSneaking = true;
            case Flip_Use_Item -> isFlipItem = true;
            case Jump -> isJump = true;
            case AttackSlow -> isAttackSlow = true;
        }
    }

    @Override
    public String toString() {
        return "VectorData{" +
                "pointThree=" + isZeroPointZeroThree +
                ", vector=" + vector +
                '}';
    }

    // TODO: This is a stupid idea that slows everything down, remove it! There are easier ways to debug grim.
    // Would make false positives really easy to fix
    // But seriously, we could trace the code to find the mistake
    public enum VectorType {
        Normal,
        Swimhop,
        Climbable,
        Knockback,
        FirstBreadKnockback,
        HackyClimbable,
        Teleport,
        SkippedTicks,
        Explosion,
        FirstBreadExplosion,
        InputResult,
        StuckMultiplier,
        Spectator,
        Dead,
        Jump,
        SurfaceSwimming,
        SwimmingSpace,
        BestVelPicked,
        Firework,
        Lenience,
        TridentJump,
        Trident,
        SlimePistonBounce,
        ZeroPointZeroThree,
        AttackSlow,
        Flip_Sneaking,
        Flip_Use_Item,
        EntityPushing
    }
}
