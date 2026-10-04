package com.almahwar.dao;

import com.almahwar.model.Role;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Data access for {@code Roles}. */
public class RoleDao extends BaseDao {

    private static final String SELECT = "SELECT role_id, role_code, role_name, description, is_active, created_at FROM dbo.Roles";

    public List<Role> findAll() {
        return queryList(SELECT + " ORDER BY role_id", RoleDao::map);
    }

    public Optional<Role> findById(int roleId) {
        return queryOne(SELECT + " WHERE role_id = ?", RoleDao::map, roleId);
    }

    public Optional<Role> findByCode(String roleCode) {
        return queryOne(SELECT + " WHERE role_code = ?", RoleDao::map, roleCode);
    }

    /** Inserts the role and sets its generated id. */
    public int insert(Role role) {
        int id = insert("INSERT INTO dbo.Roles (role_code, role_name, description, is_active) VALUES (?, ?, ?, ?)",
                role.getRoleCode(), role.getRoleName(), role.getDescription(), role.isActive());
        role.setRoleId(id);
        return id;
    }

    public void update(Role role) {
        int rows = update("UPDATE dbo.Roles SET role_code = ?, role_name = ?, description = ?, is_active = ? WHERE role_id = ?",
                role.getRoleCode(), role.getRoleName(), role.getDescription(), role.isActive(), role.getRoleId());
        requireOneRow(rows, "Role", role.getRoleId());
    }

    /** Fails with a foreign-key error while users still have this role. */
    public void delete(int roleId) {
        requireOneRow(update("DELETE FROM dbo.Roles WHERE role_id = ?", roleId), "Role", roleId);
    }

    private static Role map(ResultSet rs) throws SQLException {
        Role r = new Role();
        r.setRoleId(rs.getInt("role_id"));
        r.setRoleCode(rs.getString("role_code"));
        r.setRoleName(rs.getString("role_name"));
        r.setDescription(rs.getString("description"));
        r.setActive(rs.getBoolean("is_active"));
        r.setCreatedAt(getDateTime(rs, "created_at"));
        return r;
    }
}
