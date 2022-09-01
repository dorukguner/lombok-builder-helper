import lombok.Builder;
import lombok.NonNull;

public class Eq {

    private void createUser() {
        User.builder().name("John Smith").build();
    }

    @Builder
    class User {
        @NonNull
        String id;

        String name;
    }
}