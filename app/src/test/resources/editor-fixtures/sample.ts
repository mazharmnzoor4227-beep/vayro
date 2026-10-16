/**
 * Fixture: TypeScript sample for editor regression tests (Milestone 2).
 */

export interface User {
    id: number;
    name: string;
}

export class UserService {
    private users: User[] = [];

    addUser(user: User): void {
        this.users.push(user);
    }

    findUser(id: number): User | undefined {
        return this.users.find((u) => u.id === id);
    }
}

const service = new UserService();
service.addUser({ id: 1, name: "ada" });
const found = service.findUser(1);
console.log(found?.name);
