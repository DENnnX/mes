package com.sie.lowcode.interfaces.rpc.chain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sie.lowcode.application.*;
import com.sie.lowcode.domain.iam.*;
import com.sie.lowcode.domain.shared.DomainException;
import com.sie.lowcode.domain.shared.MetaContext;
import com.sie.lowcode.domain.uischema.*;
import com.sie.lowcode.infrastructure.persistence.sqlite.SqliteUiSchemaRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 责任链第 4 环（终环）：真正派发执行元模型治理、UI 视图菜单、IAM 认证与动态模型 RecordSet 服务。
 */
@Component
@Order(40)
public class ExecuteMetaRpcHandler implements RpcHandler {
    private final IamAppService iamAppService;
    private final UiSchemaAppService uiSchemaAppService;
    private final MetaGovernanceAppService govService;
    private final FoundationAppService foundationAppService;
    private final SqliteUiSchemaRepository uiRepo;
    private final ObjectMapper objectMapper;

    public ExecuteMetaRpcHandler(IamAppService iamAppService,
                                 UiSchemaAppService uiSchemaAppService,
                                 MetaGovernanceAppService govService,
                                 FoundationAppService foundationAppService,
                                 SqliteUiSchemaRepository uiRepo,
                                 ObjectMapper objectMapper) {
        this.iamAppService = iamAppService;
        this.uiSchemaAppService = uiSchemaAppService;
        this.govService = govService;
        this.foundationAppService = foundationAppService;
        this.uiRepo = uiRepo;
        this.objectMapper = objectMapper;
    }

    @Override
    public Object handle(RpcRequestContext ctx, Chain next) {
        String model = ctx.getModel();
        String service = ctx.getService();
        Map<String, Object> args = ctx.getArgs();

        // 1. IAM 认证、租户、组织、用户与角色入口
        if ("sys_portal".equals(model) && "publicApps".equals(service)) {
            return iamAppService.listPublicApps();
        }
        if ("rbac_user".equals(model) || "auth".equals(model)) {
            switch (service) {
                case "login":
                    String targetApp = str(args.get("appName") != null ? args.get("appName") : args.get("targetApp"));
                    return iamAppService.login(str(args.get("username")), str(args.get("password")), targetApp);
                case "logout":
                    iamAppService.logout(ctx.getAuthorizationHeader());
                    return Map.of("loggedOut", true);
                case "profile":
                    return iamAppService.buildProfileData(ctx.getUserAccount());
                case "listUsers":
                    return iamAppService.listUsers(str(args.get("tenantId")));
                case "saveUser":
                    UserAccount ua = objectMapper.convertValue(args.get("values") != null ? args.get("values") : args, UserAccount.class);
                    return iamAppService.saveUser(ua);
                case "resetPassword":
                    return iamAppService.resetPassword(str(args.get("userId")), str(args.get("newPassword")));
                case "changePassword":
                    return iamAppService.changePassword(ctx.getUserAccount(), str(args.get("oldPassword")), str(args.get("newPassword")));
                case "deleteUser":
                    return iamAppService.deleteUser(str(args.get("userId")));
                default:
                    break;
            }
        }
        if ("rbac_role".equals(model)) {
            if ("listRoles".equals(service) || "search".equals(service)) {
                return iamAppService.listRoles();
            }
            if ("saveRole".equals(service)) {
                RoleDef role = objectMapper.convertValue(args.get("values") != null ? args.get("values") : args, RoleDef.class);
                return iamAppService.saveRole(role);
            }
            if ("deleteRole".equals(service)) {
                return iamAppService.deleteRole(str(args.get("roleCode")));
            }
            if ("permissionCatalog".equals(service)) {
                return foundationAppService.buildPermissionCatalog();
            }
        }
        if ("rbac_tenant".equals(model)) {
            if ("listTenants".equals(service) || "search".equals(service)) {
                return iamAppService.listTenants();
            }
            if ("saveTenant".equals(service)) {
                TenantDef t = objectMapper.convertValue(args.get("values") != null ? args.get("values") : args, TenantDef.class);
                return iamAppService.saveTenant(t);
            }
            if ("authorizeApps".equals(service)) {
                @SuppressWarnings("unchecked")
                java.util.List<String> apps = args.get("authorizedApps") instanceof java.util.List
                        ? (java.util.List<String>) args.get("authorizedApps") : java.util.Collections.emptyList();
                return iamAppService.authorizeApps(str(args.get("tenantId")), apps);
            }
        }
        if ("rbac_org".equals(model)) {
            if ("listOrgs".equals(service) || "search".equals(service)) {
                return iamAppService.listOrgs(str(args.get("tenantId")));
            }
            if ("saveOrg".equals(service)) {
                OrgNode org = objectMapper.convertValue(args.get("values") != null ? args.get("values") : args, OrgNode.class);
                return iamAppService.saveOrg(org);
            }
            if ("deleteOrg".equals(service)) {
                return iamAppService.deleteOrg(str(args.get("orgId")));
            }
        }

        // 1.5 基础能力中心：数据字典、通用流水编码规则、附件元数据、自动 API 文档
        if ("meta_dict".equals(model)) {
            switch (service) {
                case "listTypes":
                case "search":
                    return foundationAppService.listDictTypes();
                case "saveType":
                    return foundationAppService.saveDictType(objectMapper.convertValue(args.get("values") != null ? args.get("values") : args, com.sie.lowcode.domain.metamodel.model.DictTypeDef.class));
                case "deleteType":
                    return foundationAppService.deleteDictType(str(args.get("typeCode")));
                case "listItems":
                    return foundationAppService.listDictItems(str(args.get("typeCode")));
                case "itemsByType":
                    return foundationAppService.itemsByType(str(args.get("typeCode")));
                case "saveItem":
                    return foundationAppService.saveDictItem(objectMapper.convertValue(args.get("values") != null ? args.get("values") : args, com.sie.lowcode.domain.metamodel.model.DictItemDef.class));
                case "deleteItem":
                    return foundationAppService.deleteDictItem(str(args.get("id")));
                default:
                    break;
            }
        }
        if ("meta_code_rule".equals(model)) {
            switch (service) {
                case "listRules":
                case "search":
                    return foundationAppService.listCodeRules();
                case "saveRule":
                    return foundationAppService.saveCodeRule(objectMapper.convertValue(args.get("values") != null ? args.get("values") : args, com.sie.lowcode.domain.metamodel.model.CodeRuleDef.class));
                case "deleteRule":
                    return foundationAppService.deleteCodeRule(str(args.get("code")));
                case "generateCode":
                    return foundationAppService.generateCode(str(args.get("ruleCode")));
                default:
                    break;
            }
        }
        if ("meta_file".equals(model) && ("listFiles".equals(service) || "search".equals(service))) {
            return foundationAppService.listFiles();
        }
        if ("api_doc".equals(model)) {
            if ("listCatalog".equals(service)) {
                return foundationAppService.listApiCatalog();
            }
            if ("describeModel".equals(service)) {
                return foundationAppService.describeModelApi(str(args.get("modelName")));
            }
        }

        // 2. UI 动态菜单与视图契约入口（无需依赖物理表）
        if ("ui_menu".equals(model) && "loadMenu".equals(service)) {
            return uiSchemaAppService.loadMenu();
        }
        if ("ui_view".equals(model) && "loadView".equals(service)) {
            return uiSchemaAppService.loadView(str(args.get("model")), str(args.get("type")));
        }

        // 3. 在线编辑视图与菜单定义（Dev Center 能力）
        if ("meta_view".equals(model)) {
            if ("listViews".equals(service)) {
                String m = str(args.get("modelName"));
                return (m != null && !m.isBlank()) ? uiRepo.findViewsByModel(m) : uiRepo.findAllViews();
            }
            if ("saveView".equals(service)) {
                UiViewDef v = objectMapper.convertValue(args.get("view") != null ? args.get("view") : args, UiViewDef.class);
                if (v.getKey() == null || v.getModel() == null || v.getType() == null) {
                    throw DomainException.invalidParams("视图定义缺少 key / model / type");
                }
                if (v.getId() == null) v.setId(v.getKey());
                uiRepo.saveView(v);
                return v;
            }
        }
        if ("meta_menu".equals(model)) {
            if ("listMenus".equals(service)) {
                return uiRepo.findAllMenus();
            }
            if ("saveMenu".equals(service)) {
                MenuNode mn = objectMapper.convertValue(args.get("menu") != null ? args.get("menu") : args, MenuNode.class);
                if (mn.getName() == null || mn.getDisplayName() == null) {
                    throw DomainException.invalidParams("菜单定义缺少 name / displayName");
                }
                if (mn.getId() == null) mn.setId(mn.getName());
                uiRepo.saveMenu(mn);
                return mn;
            }
        }

        // 4. 所有已注册模型（含自举 meta_app / meta_model / 用户自定义模型）统一通过 MetaContext.get(model).call(service, args) 派发
        MetaContext meta = ctx.getMetaContext();
        return meta.get(model).call(service, args);
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }
}
