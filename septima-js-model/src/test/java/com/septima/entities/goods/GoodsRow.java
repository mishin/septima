/*
 * This source file is generated automatically.
 * Please, don't edit it manually.
 */
package com.septima.entities.goods;

import java.io.Serializable;
import com.septima.model.Observable;

public class GoodsRow extends Observable {

    private static final long serialVersionUID = 1L;

    private long id;
    private String name;


    public long getId() {
        return id;
    }

    public void setId(long aValue) {
        long old = id;
        id = aValue;
        changeSupport.firePropertyChange("id", old, id);
    }
    public String getName() {
        return name;
    }

    public void setName(String aValue) {
        String old = name;
        name = aValue;
        changeSupport.firePropertyChange("name", old, name);
    }

}
