package svnserver.config;

import org.jetbrains.annotations.NotNull;
import svnserver.auth.LDAPUserDB;
import svnserver.auth.UserDB;

/**
 * @author Marat Radchenko <marat@slonopotamus.org>
 */
public final class LDAPUserDBConfig implements UserDBConfig {

  /**
   * This is a URL whose format is defined by the JNDI provider.
   * It is usually an LDAP URL that specifies the domain name of the directory server to connect to,
   * and optionally the port number and distinguished name (DN) of the required root naming context.
   * <p>
   * Example:
   */
  @NotNull
  private String connectionUrl = "ldap://localhost:389/ou=groups,dc=mycompany,dc=com";
  /**
   * The JNDI context factory used to acquire our InitialContext. By
   * default, assumes use of an LDAP server using the standard JNDI LDAP
   * provider.
   */
  @NotNull
  private String contextFactory = "com.sun.jndi.ldap.LdapCtxFactory";
  /**
   * The type of authentication to use.
   */
  @NotNull
  private String authentication = "DIGEST-MD5";
  /**
   * The search scope. Set to <code>true</code> if you wish to search the entire subtree rooted at the <code>userBase</code> entry. The default value of <code>false</code> requests a single-level search including only the top level.
   */
  private boolean userSubtree;
  /**
   * Pattern specifying the LDAP search filter to use after substitution of the username.
   */
  @NotNull
  private String userSearch = "(mail={0})";
  /**
   * LDAP attribute, containing user name.
   */
  @NotNull
  private String nameAttribute = "name";
  /**
   * LDAP attribute, containing user email.
   */
  @NotNull
  private String emailAttribute = "mail";

  @NotNull
  public String getConnectionUrl() {
    return connectionUrl;
  }

  public void setConnectionUrl(@NotNull String connectionUrl) {
    this.connectionUrl = connectionUrl;
  }

  @NotNull
  public String getContextFactory() {
    return contextFactory;
  }

  public void setContextFactory(@NotNull String contextFactory) {
    this.contextFactory = contextFactory;
  }

  @NotNull
  public String getAuthentication() {
    return authentication;
  }

  public void setAuthentication(@NotNull String authentication) {
    this.authentication = authentication;
  }

  public boolean isUserSubtree() {
    return userSubtree;
  }

  public void setUserSubtree(boolean userSubtree) {
    this.userSubtree = userSubtree;
  }

  @NotNull
  public String getUserSearch() {
    return userSearch;
  }

  public void setUserSearch(@NotNull String userSearch) {
    this.userSearch = userSearch;
  }

  @NotNull
  public String getNameAttribute() {
    return nameAttribute;
  }

  public void setNameAttribute(@NotNull String nameAttribute) {
    this.nameAttribute = nameAttribute;
  }

  @NotNull
  public String getEmailAttribute() {
    return emailAttribute;
  }

  public void setEmailAttribute(@NotNull String emailAttribute) {
    this.emailAttribute = emailAttribute;
  }

  @NotNull
  @Override
  public UserDB create() {
    return new LDAPUserDB(this);
  }
}