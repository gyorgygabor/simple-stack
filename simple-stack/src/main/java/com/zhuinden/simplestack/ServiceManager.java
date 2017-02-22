package com.zhuinden.simplestack;
/*
 * Copyright 2016 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class ServiceManager {
    static final String TAG = "simplestack.ServiceManager";

    static class RootKey
            implements Parcelable {
        private RootKey() {
        }

        protected RootKey(Parcel in) {
        }

        public static final Creator<RootKey> CREATOR = new Creator<RootKey>() {
            @Override
            public RootKey createFromParcel(Parcel in) {
                return new RootKey(in);
            }

            @Override
            public RootKey[] newArray(int size) {
                return new RootKey[size];
            }
        };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
        }

        @Override
        public boolean equals(Object object) {
            if(object == null) {
                return false;
            }
            if(object instanceof RootKey) {
                return true;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return RootKey.class.hashCode();
        }

        @Override
        public String toString() {
            return "Services.ROOT_KEY";
        }
    }

    static final Object ROOT_KEY = new RootKey();

    private final ServiceManager parent;
    private final Services rootServices;
    private final Map<Object, ReferenceCountedServices> keyToManagedServicesMap = new LinkedHashMap<>();
    private final List<ServiceFactory> servicesFactories = new ArrayList<>();

    ServiceManager(List<ServiceFactory> servicesFactories) {
        this(servicesFactories, Collections.<String, Object>emptyMap(), BackstackDelegate.DEFAULT_KEYPARCELER);
    }

    ServiceManager(List<ServiceFactory> servicesFactories, Map<String, Object> _rootServices, KeyParceler keyParceler) {
        Map<String, Object> rootServices = new LinkedHashMap<>();
        rootServices.putAll(_rootServices);
        parent = (ServiceManager) rootServices.get(TAG);
        rootServices.put(TAG, this);
        this.rootServices = new Services(ROOT_KEY, null, rootServices);
        if(parent == null) { // ROOT
            servicesFactories.add(0, new HierarchyServiceFactory(keyParceler));
        } else {
            this.servicesFactories.addAll(0, parent.servicesFactories);
        }
        this.servicesFactories.addAll(servicesFactories);
        keyToManagedServicesMap.put(ROOT_KEY, new ReferenceCountedServices(this.rootServices));
    }

    public boolean hasServices(Object key) {
        boolean hasServices = keyToManagedServicesMap.containsKey(key);
        if(!hasServices && parent != null) {
            return parent.hasServices(key);
        }
        return hasServices;
    }

    private ReferenceCountedServices findManagedServices(Object key) {
        ReferenceCountedServices managedServices = keyToManagedServicesMap.get(key);
        if(managedServices == null && parent != null) {
            managedServices = parent.findManagedServices(key);
        }
        return managedServices;
    }

    public Services findServices(Object key) {
        final ReferenceCountedServices managed = findManagedServices(key);
        if(managed == null) {
            throw new IllegalStateException("No services currently exists for key " + key);
        }
        return managed.services;
    }

    void setUp(BackstackManager backstackManager, Object key) {
        Services parent = findManagedServices(ROOT_KEY).services;
        if(key instanceof Services.Child) {
            final Object parentKey = ((Services.Child) key).parent();
            setUp(backstackManager, parentKey);
            parent = findManagedServices(parentKey).services;
        }
        ReferenceCountedServices managedService = createNonExistentManagedServicesAndIncrementUsageCount(backstackManager, parent, key);
        parent = managedService.services;
        if(key instanceof Services.Composite) {
            buildComposite(backstackManager, key, parent);
        }
    }

    private void buildComposite(BackstackManager backstackManager, Object key, Services parent) {
        Services.Composite composite = (Services.Composite) key;
        List<? extends Object> children = composite.keys();
        for(int i = 0; i < children.size(); i++) {
            Object child = children.get(i);
            ReferenceCountedServices managedServices = createNonExistentManagedServicesAndIncrementUsageCount(backstackManager,
                    parent,
                    child);
            if(child instanceof Services.Composite) {
                buildComposite(backstackManager, child, managedServices.services);
            }
        }
    }

    void tearDown(BackstackManager backstackManager, boolean shouldPersist, Object key) {
        tearDown(backstackManager, shouldPersist, key, false);
    }

    private void tearDown(BackstackManager backstackManager, boolean shouldPersist, Object key, boolean isFromComposite) {
        if(key instanceof Services.Composite) {
            Services.Composite composite = (Services.Composite) key;
            List<? extends Object> children = composite.keys();
            for(int i = children.size() - 1; i >= 0; i--) {
                tearDown(backstackManager, shouldPersist, children.get(i), true);
            }
        }
        decrementAndMaybeRemoveKey(backstackManager, shouldPersist, key);
        if(!isFromComposite && key instanceof Services.Child) {
            tearDown(backstackManager, shouldPersist, ((Services.Child) key).parent(), false);
        }
    }

    @NonNull
    private ReferenceCountedServices createNonExistentManagedServicesAndIncrementUsageCount(BackstackManager backstackManager, @NonNull Services parentServices, Object key) {
        ReferenceCountedServices node = findManagedServices(key);
        if(node == null) {
            // @formatter:off
            // Bind the local key as a service.
            @SuppressWarnings("ConstantConditions")
            Services.Builder builder = parentServices.extend(key);
            // @formatter:on

            // Add any services from the factories
            for(int i = 0, size = servicesFactories.size(); i < size; i++) {
                servicesFactories.get(i).bindServices(builder);
            }
            node = new ReferenceCountedServices(builder.build());
            keyToManagedServicesMap.put(key, node);
            restoreServicesForKey(backstackManager, key);
        }
        node.usageCount++;
        return node;
    }

    void restoreServicesForKey(BackstackManager backstackManager, Object key) {
        ReferenceCountedServices node = findManagedServices(key);
        SavedState savedState = backstackManager.getSavedState(key);
        StateBundle bundle = savedState.getServiceBundle();
        if(bundle != null) {
            //Log.i("ServiceManager", "<<< RESTORE [" + key + "] >>>");
            for(Map.Entry<String, Object> serviceEntry : node.services.ownedServices.entrySet()) {
                if(serviceEntry.getValue() instanceof Bundleable) {
                    StateBundle serviceBundle = bundle.getBundle(serviceEntry.getKey());
                    ((Bundleable) serviceEntry.getValue()).fromBundle(serviceBundle);
                }
            }
        }
    }

    void persistServicesForKey(BackstackManager backstackManager, Object key) {
        //Log.i("ServiceManager", "<<< PERSIST [" + key + "] >>>");
        ReferenceCountedServices node = findManagedServices(key);
        SavedState savedState = backstackManager.getSavedState(key);
        StateBundle bundle = savedState.getServiceBundle();
        for(Map.Entry<String, Object> serviceEntry : node.services.ownedServices.entrySet()) {
            if(serviceEntry.getValue() instanceof Bundleable) {
                bundle.putBundle(serviceEntry.getKey(), ((Bundleable) serviceEntry.getValue()).toBundle());
            }
        }
    }

    void persistServicesForKeyHierarchy(BackstackManager backstackManager, Object key) {
        if(key instanceof Services.Child) {
            persistServicesForKeyHierarchy(backstackManager, ((Services.Child) key).parent());
        }
        persistServicesForKey(backstackManager, key);
        if(key instanceof Services.Composite) {
            persistComposite(backstackManager, key);
        }
    }

    private void persistComposite(BackstackManager backstackManager, Object key) {
        Services.Composite composite = (Services.Composite) key;
        List<? extends Object> children = composite.keys();
        for(int i = 0; i < children.size(); i++) {
            Object child = children.get(i);
            persistServicesForKey(backstackManager, child);
            if(child instanceof Services.Composite) {
                persistComposite(backstackManager, child);
            }
        }
    }

    private boolean decrementAndMaybeRemoveKey(BackstackManager backstackManager, boolean shouldPersist, Object key) {
        ReferenceCountedServices node = findManagedServices(key);
        if(node == null) {
            throw new IllegalStateException("Cannot remove a node that doesn't exist or has already been removed!");
        }
        node.usageCount--;
        if(key != ROOT_KEY && node.usageCount == 0) {
            if(shouldPersist) {
                persistServicesForKey(backstackManager, key);
            }
            int count = servicesFactories.size();
            for(int i = count - 1; i >= 0; i--) {
                servicesFactories.get(i).tearDownServices(node.services);
            }
            keyToManagedServicesMap.remove(key);
            return true;
        }
        return false;
    }

    private static final class ReferenceCountedServices {
        final Services services;

        int usageCount = 0;

        private ReferenceCountedServices(Services services) {
            this.services = services;
        }
    }

    Context createContext(Context base, Object key) {
        return new ManagedContextWrapper(base, key, findServices(key));
    }

    public void dumpLogData() {
        Log.i("ServiceManager", "Services: ");
        for(Map.Entry<Object, ReferenceCountedServices> entry : keyToManagedServicesMap.entrySet()) {
            Log.i("ServiceManager", "  [" + entry.getKey() + "] :: " + entry.getValue().usageCount);
        }
    }
}