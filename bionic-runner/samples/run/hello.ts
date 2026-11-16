// 実行モード用のサンプル:  /run hello.ts run
interface Fruit {
  name: string;
  price: number;
}

const fruits: Fruit[] = [
  { name: "apple", price: 120 },
  { name: "banana", price: 80 },
];

const total: number = fruits.reduce((sum, f) => sum + f.price, 0);
const a: string = "a";
print(a);
console.log(fruits.map(f => f.name).join(", "), total);
