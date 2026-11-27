package com.edu.scnu.domain.iam;

import java.util.List;
import java.util.Optional;

public interface IamRepository {
    Optional<UserAccount> findUserByUsername(String username);
    Optional<UserAccount> findUserById(String id);
    List<UserAccount> findAllUsers();
    List<UserAccount> findUsersByTenant(String tenantId);
    void saveUser(UserAccount user);
    void deleteUser(String userId);

    List<RoleDef> findRolesByCodes(Iterable<String> codes);
    List<RoleDef> findAllRoles();
    void saveRole(RoleDef role);
    void deleteRole(String roleCode);

    List<TenantDef> findAllTenants();
    Optional<TenantDef> findTenantById(String id);
    Optional<TenantDef> findTenantByCode(String code);
    void saveTenant(TenantDef tenant);
    void deleteTenant(String tenantId);

    List<OrgNode> findOrgsByTenant(String tenantId);
    void saveOrg(OrgNode org);
    void deleteOrg(String orgId);
    void deleteOrgsByTenant(String tenantId);

    void saveSession(String token, String userId, String expiresAt);
    Optional<String> findUserIdByToken(String token);
    void deleteSession(String token);
}
