/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.data;

import org.apache.lucene.util.RamUsageEstimator;
import org.elasticsearch.common.util.DoubleArray;
import org.elasticsearch.core.Releasable;

/**
 * Vector implementation that defers to an enclosed DoubleArray.
 * This class is generated. Do not edit it.
 */
public final class DoubleBigArrayVector extends AbstractVector implements DoubleVector, Releasable {

    private static final long BASE_RAM_BYTES_USED = RamUsageEstimator.shallowSizeOfInstance(DoubleBigArrayVector.class);

    private final DoubleArray values;

    public DoubleBigArrayVector(DoubleArray values, int positionCount) {
        this(values, positionCount, BlockFactory.getNonBreakingInstance());
    }

    public DoubleBigArrayVector(DoubleArray values, int positionCount, BlockFactory blockFactory) {
        super(positionCount, blockFactory);
        this.values = values;
    }

    @Override
    public DoubleBlock asBlock() {
        return new DoubleVectorBlock(this);
    }

    @Override
    public double getDouble(int position) {
        return values.get(position);
    }

    @Override
    public ElementType elementType() {
        return ElementType.DOUBLE;
    }

    @Override
    public boolean isConstant() {
        return false;
    }

    @Override
    public long ramBytesUsed() {
        return BASE_RAM_BYTES_USED + RamUsageEstimator.sizeOf(values);
    }

    @Override
    public DoubleVector filter(int... positions) {
        return new FilterDoubleVector(this, positions);
    }

    @Override
    public void close() {
        values.close();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof DoubleVector that) {
            return DoubleVector.equals(this, that);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return DoubleVector.hash(this);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[positions=" + getPositionCount() + ", values=" + values + ']';
    }
}
