/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.clustering.algo;

import com.example.clustering.model.CustomClusterItem;
import com.google.android.gms.maps.model.LatLng;
import com.google.maps.android.clustering.Cluster;
import com.google.maps.android.clustering.algo.AbstractAlgorithm;
import com.google.maps.android.clustering.algo.StaticCluster;
import com.google.maps.android.geometry.Bounds;
import com.google.maps.android.geometry.Point;
import com.google.maps.android.projection.SphericalMercatorProjection;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A simple clustering algorithm with O(nlog n) performance. Resulting clusters are not
 * hierarchical.
 * <p>
 * High level algorithm:<br>
 * 1. Iterate over items in the order they were added (candidate clusters).<br>
 * 2. Create a cluster with the center of the item. <br>
 * 3. Add all items that are within a certain distance to the cluster. <br>
 * 4. Move any items out of an existing cluster if they are closer to another cluster. <br>
 * 5. Remove those items from the list of candidate clusters.
 * <p>
 * Clusters have the center of the first element (not the centroid of the items within it).
 */
public class CustomNonHierarchicalDistanceBasedAlgorithm<T extends CustomClusterItem> extends AbstractAlgorithm<T> {


    /**
     * Any modifications should be synchronized on mQuadTree.
     */
    private final Collection<CustomQuadItem<T>> mItems = new LinkedHashSet<>();

    /**
     * Any modifications should be synchronized on mQuadTree.
     */
    private final CustomPointQuadTree<CustomQuadItem<T>> mQuadTree = new CustomPointQuadTree<>(0, 1, 0, 1);

    private static final SphericalMercatorProjection PROJECTION = new SphericalMercatorProjection(1);

    /**
     * Adds an item to the algorithm
     *
     * @param item the item to be added
     * @return true if the algorithm contents changed as a result of the call
     */
    @Override
    public boolean addItem(T item) {
        boolean result;
        final CustomQuadItem<T> quadItem = new CustomQuadItem<>(item);
        synchronized (mQuadTree) {
            result = mItems.add(quadItem);
            if (result) {
                mQuadTree.add(quadItem);
            }
        }
        return result;
    }

    /**
     * Adds a collection of items to the algorithm
     *
     * @param items the items to be added
     * @return true if the algorithm contents changed as a result of the call
     */
    @Override
    public boolean addItems(Collection<T> items) {
        boolean result = false;
        for (T item : items) {
            boolean individualResult = addItem(item);
            if (individualResult) {
                result = true;
            }
        }
        return result;
    }

    @Override
    public void clearItems() {
        synchronized (mQuadTree) {
            mItems.clear();
            mQuadTree.clear();
        }
    }

    /**
     * Removes an item from the algorithm
     *
     * @param item the item to be removed
     * @return true if this algorithm contained the specified element (or equivalently, if this
     * algorithm changed as a result of the call).
     */
    @Override
    public boolean removeItem(T item) {
        boolean result;
        // QuadItem delegates hashcode() and equals() to its item so,
        //   removing any QuadItem to that item will remove the item
        final CustomQuadItem<T> quadItem = new CustomQuadItem<>(item);
        synchronized (mQuadTree) {
            result = mItems.remove(quadItem);
            if (result) {
                mQuadTree.remove(quadItem);
            }
        }
        return result;
    }

    /**
     * Removes a collection of items from the algorithm
     *
     * @param items the items to be removed
     * @return true if this algorithm contents changed as a result of the call
     */
    @Override
    public boolean removeItems(Collection<T> items) {
        boolean result = false;
        synchronized (mQuadTree) {
            for (T item : items) {
                // QuadItem delegates hashcode() and equals() to its item so,
                //   removing any QuadItem to that item will remove the item
                final CustomQuadItem<T> quadItem = new CustomQuadItem<>(item);
                boolean individualResult = mItems.remove(quadItem);
                if (individualResult) {
                    mQuadTree.remove(quadItem);
                    result = true;
                }
            }
        }
        return result;
    }

    /**
     * Updates the provided item in the algorithm
     *
     * @param item the item to be updated
     * @return true if the item existed in the algorithm and was updated, or false if the item did
     * not exist in the algorithm and the algorithm contents remain unchanged.
     */
    @Override
    public boolean updateItem(T item) {
        // TODO - Can this be optimized to update the item in-place if the location hasn't changed?
        boolean result;
        synchronized (mQuadTree) {
            result = removeItem(item);
            if (result) {
                // Only add the item if it was removed (to help prevent accidental duplicates on map)
                result = addItem(item);
            }
        }
        return result;
    }

    @Override
    public Set<? extends Cluster<T>> getClusters(float zoom) {

        final Set<CustomQuadItem<T>> visitedCandidates = new HashSet<>();
        final Set<Cluster<T>> results = new HashSet<>();
        final Map<CustomQuadItem<T>, Double> distanceToCluster = new HashMap<>();
        final Map<CustomQuadItem<T>, StaticCluster<T>> itemToCluster = new HashMap<>();

        synchronized (mQuadTree) {
            for (CustomQuadItem<T> candidate : mItems) {
                if (visitedCandidates.contains(candidate)) {
                    // Candidate is already part of another cluster.
                    continue;
                }

                Bounds searchBounds = createBoundsFromDistance(candidate.getPoint(),1000);
                Collection<CustomQuadItem<T>> clusterItems;
                clusterItems = mQuadTree.search(searchBounds, candidate.getValue());

                if (clusterItems.size() == 1) {
                    // Only the current marker is in range. Just add the single item to the results.
                    results.add(candidate);
                    visitedCandidates.add(candidate);
                    distanceToCluster.put(candidate, 0d);
                    continue;
                }
                StaticCluster<T> cluster = new StaticCluster<>(candidate.mClusterItem.getPosition());
                results.add(cluster);

                for (CustomQuadItem<T> clusterItem : clusterItems) {
                    Double existingDistance = distanceToCluster.get(clusterItem);
                    double distance = distanceSquared(clusterItem.getPoint(), candidate.getPoint());
                    if (existingDistance != null) {
                        // Item already belongs to another cluster. Check if it's closer to this cluster.
                        if (existingDistance < distance) {
                            continue;
                        }
                        // Move item to the closer cluster.
                        itemToCluster.get(clusterItem).remove(clusterItem.mClusterItem);
                    }
                    distanceToCluster.put(clusterItem, distance);
                    cluster.add(clusterItem.mClusterItem);
                    itemToCluster.put(clusterItem, cluster);
                }
                visitedCandidates.addAll(clusterItems);
            }
        }
        return results;
    }


    @Override
    public Collection<T> getItems() {
        final Set<T> items = new LinkedHashSet<>();
        synchronized (mQuadTree) {
            for (CustomQuadItem<T> quadItem : mItems) {
                items.add(quadItem.mClusterItem);
            }
        }
        return items;
    }

    @Override
    public void setMaxDistanceBetweenClusteredItems(int maxDistance) {

    }

    @Override
    public int getMaxDistanceBetweenClusteredItems() {
        return 0;
    }

    private double distanceSquared(Point a, Point b) {
        return (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y);
    }

    protected static class CustomQuadItem<T extends CustomClusterItem> implements CustomPointQuadTree.Item, Cluster<T> {
        private final T mClusterItem;
        private final Point mPoint;
        private final LatLng mPosition;
        private Set<T> singletonSet;

        private final double mValue;

        private CustomQuadItem(T item) {
            mClusterItem = item;
            mPosition = item.getPosition();
            mPoint = PROJECTION.toPoint(mPosition);
            mValue = item.getLabelValue();
            singletonSet = Collections.singleton(mClusterItem);
        }

        @Override
        public Point getPoint() {
            return mPoint;
        }

        @Override
        public double getValue() {
            return mValue;
        }

        @Override
        public LatLng getPosition() {
            return mPosition;
        }

        @Override
        public Set<T> getItems() {
            return singletonSet;
        }

        @Override
        public int getSize() {
            return 1;
        }

        @Override
        public int hashCode() {
            return mClusterItem.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof CustomQuadItem<?>)) {
                return false;
            }

            return ((CustomQuadItem<?>) other).mClusterItem.equals(mClusterItem);
        }
    }

    private Bounds createBoundsFromDistance(Point center, double distance) {
        double metersToPoints = 1 / (2 * Math.PI * 6371000); // Assuming Earth's radius is 6371000 meters

        double distancePoints = (distance * metersToPoints) * 1.5;

        double minX = center.x - distancePoints;
        double maxX = center.x + distancePoints;
        double minY = center.y - distancePoints;
        double maxY = center.y + distancePoints;
        return new Bounds(minX, maxX, minY, maxY);
    }
}
