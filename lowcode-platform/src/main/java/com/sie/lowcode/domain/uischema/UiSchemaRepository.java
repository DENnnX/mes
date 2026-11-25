package com.sie.lowcode.domain.uischema;

import java.util.List;
import java.util.Optional;

public interface UiSchemaRepository {
    void saveView(UiViewDef view);
    Optional<UiViewDef> findViewByKey(String key);
    List<UiViewDef> findViewsByModel(String modelName);
    List<UiViewDef> findAllViews();
    void deleteViewsByModel(String modelName);

    void saveMenu(MenuNode menu);
    Optional<MenuNode> findMenuByName(String name);
    List<MenuNode> findAllMenus();
    void deleteMenusByModel(String modelName);
    void deleteMenusByApp(String appName);
}
