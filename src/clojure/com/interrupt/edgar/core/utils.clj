(ns com.interrupt.edgar.core.utils)


(defn sine
  "This is a basic sine equation. Below is a simple Clojure implementation, based on the algebra we've written.

  f(x) = a sin (b(x − c)) + d
  y = a sin (b(x − c)) + d
  y = a sin (b(x − Pi/2)) + d

  f(x) = y
  a - is the amplitude of the wave (makes the wave taller or sharter)
  b - is the horizontal dilation (makes the wave wider or thinner)
  c - Pi/2 , is the horizontal translation (moves the wave left or right)
  d - is the vertical translation (moves the wave up or down)

  Mapping this function over an integer range of -5 to 5, provides the accompanying y values.

  (map #(sine 2 2 % 0) '(-5 -4 -3 -2 -1 0 1 2 3 4 5))
  (-1.0880422217787393 1.9787164932467636 -0.5588309963978519 -1.5136049906158564 1.8185948536513634 -2.4492935982947064E-16
  -1.8185948536513632 1.5136049906158566 0.5588309963978515 -1.9787164932467636 1.0880422217787398)"
  [a b input d]
  (- (* a
        (Math/sin (* b
                     (- input
                        (/ Math/PI 2)))))
     d))
