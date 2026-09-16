package pl.wolftaxi.app.domain.operator;

import java.util.ArrayList;

public final class UserAccount {
    public String id = "";
    public String email = "";
    public String name = "";
    public ArrayList<String> roles = new ArrayList<>();
    public boolean enabled = true;
}
