package com.zhuinden.simpleservicesexample.presentation.paths.h;

import com.google.auto.value.AutoValue;
import com.zhuinden.servicetree.ServiceTree;
import com.zhuinden.simpleservicesexample.R;
import com.zhuinden.simpleservicesexample.application.Key;
import com.zhuinden.simpleservicesexample.utils.MockService;


/**
 * Created by Owner on 2017. 02. 16..
 */

@AutoValue
public abstract class H
        extends Key {
    public static H create() {
        return new AutoValue_H();
    }

    @Override
    public int layout() {
        return R.layout.path_h;
    }

    @Override
    public void bindServices(ServiceTree.Node node) {
        node.bindService("H", new MockService("H"));
    }
}
