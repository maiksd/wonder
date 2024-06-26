//
//  ERXNavigationManager.java
//  ERExtensions
//
//  Created by Max Muller on Wed Oct 30 2002.
//
package er.extensions.appserver.navigation;

import java.net.URL;
import java.util.Enumeration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver.WOApplication;
import com.webobjects.appserver.WOSession;
import com.webobjects.foundation.NSArray;
import com.webobjects.foundation.NSDictionary;
import com.webobjects.foundation.NSMutableArray;
import com.webobjects.foundation.NSMutableDictionary;
import com.webobjects.foundation.NSNotification;
import com.webobjects.foundation.NSSelector;
import com.webobjects.foundation._NSUtilities;

import er.extensions.appserver.ERXApplication;
import er.extensions.eof.ERXConstant;
import er.extensions.foundation.ERXFileNotificationCenter;
import er.extensions.foundation.ERXFileUtilities;
import er.extensions.foundation.ERXPatcher;
import er.extensions.foundation.ERXProperties;
import er.extensions.foundation.ERXUtilities;

/** Please read "Documentation/Navigation.html" to fnd out how to use the navigation components.*/
public class ERXNavigationManager {
    private static final Logger log = LoggerFactory.getLogger(ERXNavigationManager.class);
    
    protected static ERXNavigationManager manager;
    
    public static String NAVIGATION_MAP_KEY = "navigationFlightPlan";

    public static ERXNavigationManager manager() {
        if (manager == null)
            manager = new ERXNavigationManager();
        return manager;
    }

    protected NSDictionary<String, ERXNavigationItem> navigationItemsByName = NSDictionary.EmptyDictionary;
    protected ERXNavigationItem rootNavigationItem;
    protected String navigationMenuFileName;
    protected boolean hasRegistered = false;
    
    public ERXNavigationState navigationStateForSession(WOSession session) {
        ERXNavigationState state = (ERXNavigationState)session.objectForKey(navigationStateSessionKey());
        if (state == null) {
            state = new ERXNavigationState();
            session.setObjectForKey(state, navigationStateSessionKey());
        }
        return state;
    }
    
	/**
	 * If the current request was created via a link on the navigation menu,
	 * this will retrieve the matching navigation state as a string.
	 * 
	 * @param session
	 * @return the applicable navigation state as a string or null if it is
	 *         undefined
	 */
	public String navigationStateFromMap(WOSession session) {
		String navigationState = null;
		if (session.valueForKeyPath("objectStore." + ERXNavigationManager.NAVIGATION_MAP_KEY) != null) {
			// yes, the request was generated by a click on the navigation menu
			NSDictionary navigationDictionary = (NSDictionary) session.valueForKeyPath("objectStore." + 
					ERXNavigationManager.NAVIGATION_MAP_KEY);
			// the current request's URI serves as the key
			String keyPath = session.context().request().uri().replace('.', '_');
			// store it as it will be called
			keyPath = ERXApplication.erxApplication()._rewriteURL(keyPath);
			navigationState = (String) navigationDictionary.objectForKey(keyPath);
			if (navigationState != null) {
				// check whether the item has a default child
				ERXNavigationItem navigationItem = ERXNavigationManager.manager().navigationItemForName(
						NSArray.componentsSeparatedByString(navigationState, ".").lastObject());
				// recurse until all default children choices have been considered
				while (navigationItem != null && navigationItem.defaultChild() != null) {
					log.debug("Replacing navigation state '{}' with default child's state '{}'", navigationState, 
						navigationState.concat("." + navigationItem.defaultChild()));
					// change navigation state to that of the defaultChild
					navigationState = navigationState.concat("." + navigationItem.defaultChild());
					navigationItem = navigationItemForName(navigationItem.defaultChild());
				}
				// clear stale entries from flight plan to prevent it from growing
				navigationDictionary = new NSMutableDictionary(navigationState, keyPath);
				session.takeValueForKeyPath(navigationDictionary, "objectStore." + NAVIGATION_MAP_KEY);
			}
		}
		return navigationState;
	}

	/**
	 * Stores a navigation item's fully qualified state (e.g.
	 * "Movies.SearchMovie") under the corresponding URI for retrieval on the
	 * next request.
	 * 
	 * @param session 
	 * @param navigationItem
	 * @param uri
	 */
	public void storeInNavigationMap(WOSession session, ERXNavigationItem navigationItem, String uri) {
		// we group the keys in a dictionary to allow easy removal
		NSMutableDictionary<String, String> navigationFlightPlan = (NSMutableDictionary<String, String>) 
				session.objectForKey(ERXNavigationManager.NAVIGATION_MAP_KEY);
		if (navigationFlightPlan == null) {
			navigationFlightPlan = new NSMutableDictionary<>();
		}
		if (uri != null) {
			navigationFlightPlan.setObjectForKey(navigationItem.navigationPath().replace('/', '.'), uri.replace('.', '_'));
			session.setObjectForKey(navigationFlightPlan, ERXNavigationManager.NAVIGATION_MAP_KEY);
		}
	}
	
    public String navigationStateSessionKey() {
        return "NavigationState";
    }

    public String navigationMenuFileName() {
        if (navigationMenuFileName == null) {
            navigationMenuFileName = ERXProperties.stringForKeyWithDefault("er.extensions.ERXNavigationManager.NavigationMenuFileName", "NavigationMenu.plist");
        }
        return navigationMenuFileName;
    }

    public void setNavigationMenuFileName(String name) {
        navigationMenuFileName = name;
    }
    
    public NSDictionary<String, ERXNavigationItem> navigationItemsByName() {
        return navigationItemsByName;
    }
    
    public ERXNavigationItem rootNavigationItem() {
        return rootNavigationItem;
    }

    public ERXNavigationItem navigationItemForName(String name) {
        return navigationItemsByName.objectForKey(name);
    }

    protected void setNavigationItems(NSArray items) {
        NSMutableDictionary<String, ERXNavigationItem> itemsByName = new NSMutableDictionary<>();
        if (items != null && items.count() > 0) {
            for (Enumeration e = items.objectEnumerator(); e.hasMoreElements();) {
                ERXNavigationItem item = (ERXNavigationItem)e.nextElement();
                if (itemsByName.objectForKey(item.name()) != null) {
                    log.warn("Attempting to register multiple navigation items for the same name: {}", item.name());
                } else {
                    itemsByName.setObjectForKey(item, item.name());
                    if (item.name().equals("Root"))
                        rootNavigationItem = item;
                }
            }
        }
        if (rootNavigationItem == null)
            log.warn("No root navigation item set. You need one.");
        navigationItemsByName = itemsByName.immutableClone();
    }
    
    public void configureNavigation() {
        loadNavigationMenu();
        hasRegistered = true;
    }
    
    public void loadNavigationMenu() {
        NSMutableArray navigationMenus = new NSMutableArray();
        // First load the nav_menu from application.
        NSArray appNavigationMenu = (NSArray)ERXFileUtilities.readPropertyListFromFileInFramework(navigationMenuFileName(), null);
        if (appNavigationMenu != null) {
            log.debug("Found navigation menu in application: {}", WOApplication.application().name());
            navigationMenus.addObjectsFromArray(createNavigationItemsFromDictionaries(appNavigationMenu));
            registerObserverForFramework(null);
        }
        for (Enumeration e = ERXUtilities.allFrameworkNames().objectEnumerator(); e.hasMoreElements();) {
            String frameworkName = (String)e.nextElement();
            NSArray aNavigationMenu = (NSArray)ERXFileUtilities.readPropertyListFromFileInFramework(navigationMenuFileName(), frameworkName);
            if (aNavigationMenu != null && aNavigationMenu.count() > 0) {
                log.debug("Found navigation menu in framework: {}", frameworkName);
                navigationMenus.addObjectsFromArray(createNavigationItemsFromDictionaries(aNavigationMenu));
                registerObserverForFramework(frameworkName);
            }
        }
        setNavigationItems(navigationMenus);
        // compute default child items
        NSMutableDictionary<Object, String> fakeContext = new NSMutableDictionary<>();
        for (ERXNavigationItem anItem : navigationItemsByName().allValues()) {
        	anItem.childItemsInContext(fakeContext);
        }
        // set defaultChild values where actions match
        for (String anItemName : navigationItemsByName().allKeys()) {
        	ERXNavigationItem anItem = navigationItemsByName().objectForKey(anItemName);
			if (anItem.children() != null && anItem.children().count() > 0 && anItem.defaultChild() == null) {
				for (String aChildName : anItem.children()) {
					ERXNavigationItem aChild = navigationItemsByName().objectForKey(aChildName);
					if (aChild != null) {
						if (anItem.action() != null && anItem.action().equals(aChild.action())) {
							anItem.setDefaultChild(aChild.name());
							break;
						}
						else if (anItem.directActionName() != null && anItem.directActionName().equals(aChild.directActionName())) {
							anItem.setDefaultChild(aChild.name());
							break;
						}
						else if (anItem.href() != null && anItem.href().equals(aChild.href())) {
							anItem.setDefaultChild(aChild.name());
							break;
						} else if (anItem.pageName() != null && anItem.pageName().equals(aChild.pageName())) {
							anItem.setDefaultChild(aChild.name());
							break;
						}
					} else {
						log.warn("You set an undefined child on the item {}", anItem.name());
					}
				}
			} 
        }
        log.debug("Navigation Menu Configured");
    }

    public void registerObserverForFramework(String frameworkName) {
        if (!WOApplication.application().isCachingEnabled() && !hasRegistered) {
        	URL filePathUrl = ERXFileUtilities.pathURLForResourceNamed(navigationMenuFileName(), frameworkName, null);
        	if(filePathUrl != null) {
        		String filePath = filePathUrl.getFile();
        		log.debug("Registering observer for filePath: {}", filePath);
        		ERXFileNotificationCenter.defaultCenter().addObserver(this,
        				new NSSelector("reloadNavigationMenu", ERXConstant.NotificationClassArray),
        				filePath);
        	}
        	else {
        		log.error("failed to registerObserverForFramework {}: Can't get filePathUrl for {}", frameworkName, navigationMenuFileName());
        	}
        }
    }

    public ERXNavigationItem newNavigationItem(NSDictionary dict) {
    	String className = (String) dict.objectForKey("navigationItemClassName");
    	if(className != null) {
    		Class c = ERXPatcher.classForName(className);
    		return (ERXNavigationItem) _NSUtilities.instantiateObject(c, new Class[] {NSDictionary.class}, new Object[]{dict}, true, true);
    	}
    	return new ERXNavigationItem(dict);
    }
    
    protected NSArray createNavigationItemsFromDictionaries(NSArray navItems) {
        NSMutableArray navigationItems = null;
        if (navItems != null && navItems.count() > 0) {
            navigationItems = new NSMutableArray();
            for (Enumeration e = navItems.objectEnumerator(); e.hasMoreElements();) {
                navigationItems.addObject(newNavigationItem((NSDictionary)e.nextElement()));
            }
        }
        return navigationItems != null ? navigationItems : NSArray.EmptyArray;
    }
    
    public void reloadNavigationMenu(NSNotification notification) {
        log.info("Reloading Navigation Menu");
        loadNavigationMenu();
    }
}
